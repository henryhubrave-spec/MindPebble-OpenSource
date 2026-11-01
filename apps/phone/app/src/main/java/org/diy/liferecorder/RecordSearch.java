package org.diy.liferecorder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.*;
import java.util.*;

/** Rebuildable, device-only lexical index. It deliberately makes no embedding/semantic claim. */
final class RecordSearch extends SQLiteOpenHelper {
    interface Progress { void update(int done,int total); }
    static final class Hit {
        final String source,title,text,kind,unit; final long created,start,end,revision; final boolean timed; final double score;
        Hit(Cursor c,double score){source=c.getString(0);title=c.getString(1);text=c.getString(2);kind=c.getString(3);unit=c.getString(4);created=c.getLong(5);start=c.getLong(6);end=c.getLong(7);timed=c.getInt(8)!=0;revision=c.getLong(9);this.score=score;}
    }
    static final class Result { final List<Hit> hits;final List<String> terms;final int total;Result(List<Hit> h,List<String> t,int n){hits=h;terms=t;total=n;} }
    RecordSearch(Context context){super(context,"record-search.db",null,2);}
    public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE sync_state(id INTEGER PRIMARY KEY,version INTEGER NOT NULL)");db.execSQL("INSERT INTO sync_state VALUES(1,0)");db.execSQL("CREATE TABLE sources(id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,created INTEGER NOT NULL,revision INTEGER NOT NULL)");db.execSQL("CREATE INDEX source_date ON sources(created)");db.execSQL("CREATE VIRTUAL TABLE passages USING fts4(content,source,title,original,kind,unit,start_ms,end_ms,timed,tokenize=unicode61,notindexed=source,notindexed=title,notindexed=original,notindexed=kind,notindexed=unit,notindexed=start_ms,notindexed=end_ms,notindexed=timed)");}
    public void onUpgrade(SQLiteDatabase db,int old,int version){db.execSQL("DROP TABLE IF EXISTS sync_state");db.execSQL("DROP TABLE IF EXISTS passages");db.execSQL("DROP TABLE IF EXISTS sources");onCreate(db);}
    static void forget(Context context,String source){if(!context.getDatabasePath("record-search.db").isFile())return;try(RecordSearch index=new RecordSearch(context)){SQLiteDatabase db=index.getWritableDatabase();db.beginTransaction();try{db.delete("passages","source=?",new String[]{source});db.delete("sources","id=?",new String[]{source});db.setTransactionSuccessful();}finally{db.endTransaction();}}}
    void sync(RecordStore store,Progress progress)throws Exception {
        SQLiteDatabase db=getWritableDatabase();
        while(true){
            long version;try(Cursor cursor=db.rawQuery("SELECT version FROM sync_state WHERE id=1",null)){cursor.moveToFirst();version=cursor.getLong(0);}
            // Read evidence and its revision in the same source transaction. Commit the index
            // cursor with the passages so a crash or concurrent edit cannot lose invalidations.
            SQLiteDatabase source=store.getWritableDatabase();source.beginTransactionNonExclusive();
            try{
                JSONArray changes=store.rows("SELECT source,version FROM search_changes WHERE version>? ORDER BY version,source LIMIT 250",String.valueOf(version));
                if(changes.length()==0){source.setTransactionSuccessful();return;}
                // Initial migration assigns the same revision to all sources. Include ties.
                long end=changes.getJSONObject(changes.length()-1).getLong("version");
                changes=store.rows("SELECT source,version FROM search_changes WHERE version>? AND version<=? ORDER BY version,source",String.valueOf(version),String.valueOf(end));
                db.beginTransaction();try{
                    for(int i=0;i<changes.length();i++){
                        if(Thread.currentThread().isInterrupted())throw new java.io.IOException("搜索已取消");
                        String id=changes.getJSONObject(i).getString("source");
                        db.delete("passages","source=?",new String[]{id});db.delete("sources","id=?",new String[]{id});
                        JSONArray current=store.rows("SELECT * FROM audio WHERE id=?",id);
                        if(current.length()>0){
                            JSONObject audio=current.getJSONObject(0);JSONArray units=TranscriptDocument.units(audio);
                            for(int n=0;n<units.length();n++){JSONObject u=units.getJSONObject(n);String value=u.optString("text");if(u.optBoolean("timed"))insert(db,audio,value,"transcript",u.optString("unit_id"),u.optLong("start_ms"),u.optLong("end_ms"),true);else for(int at=0;at<value.length();at+=700)insert(db,audio,value.substring(at,Math.min(value.length(),at+800)),"transcript",u.optString("unit_id")+":"+at,0,0,false);}
                            insert(db,audio,audio.optString("title"),"title",id+":title",0,0,false);
                            JSONArray memories=store.rows("SELECT id,text FROM facts WHERE source=? AND state='accepted' ORDER BY id",id);
                            for(int n=0;n<memories.length();n++){JSONObject f=memories.getJSONObject(n);insert(db,audio,f.optString("text"),"memory",f.optString("id"),0,0,false);}
                            ContentValues v=new ContentValues();v.put("id",id);v.put("fingerprint","");v.put("created",audio.optLong("created"));v.put("revision",audio.optLong("transcript_revision"));db.insertOrThrow("sources",null,v);
                        }
                        if(progress!=null&&(i%20==0||i==changes.length()-1))progress.update(i+1,changes.length());
                    }
                    db.execSQL("UPDATE sync_state SET version=? WHERE id=1",new Object[]{end});db.setTransactionSuccessful();
                }finally{db.endTransaction();}
                source.setTransactionSuccessful();
            }finally{source.endTransaction();}
        }
    }
    private static void insert(SQLiteDatabase db,JSONObject audio,String value,String kind,String unit,long start,long end,boolean timed){if(value.trim().isEmpty())return;ContentValues v=new ContentValues();v.put("content",LifeMemory.tokens(value.toLowerCase(Locale.ROOT)));v.put("source",audio.optString("id"));v.put("title",audio.optString("title"));v.put("original",value);v.put("kind",kind);v.put("unit",unit);v.put("start_ms",start);v.put("end_ms",end);v.put("timed",timed?1:0);db.insertOrThrow("passages",null,v);}
    static List<String> terms(String query){
        String clean=query.toLowerCase(Locale.ROOT).replaceAll("(?:帮我|帮忙|找一下|找找|找回|搜索|关于|那次|上次|之前|最近|我想知道|我记得|什么时候|是什么|是什么时候|有没有|的事情|说过|记录|最后|什么|如何|怎么|一下)"," ");LinkedHashSet<String> terms=new LinkedHashSet<>();
        for(String token:LifeMemory.tokens(clean).split("\\s+"))if(token.length()>=2&&token.length()<=40&&!token.matches("(?:我们|你们|他们|这个|那个|一次|时候|相关|事情|讨论)")){terms.add(token);if(terms.size()>=40)break;}
        String[][] related={{"决定","定了","确定","定稿","拍板","方案"},{"截止","提交","交付","期限"},{"会议","开会","讨论"},{"购买","买了","下单"},{"运动","跑步","健身"},{"学习","学会","课程"}};
        for(String[] group:related){boolean match=false;for(String t:group)if(clean.contains(t))match=true;if(match)for(String t:group)terms.add(t);}return new ArrayList<>(terms);
    }
    Result find(String query,long start,long end)throws Exception {
        List<String> terms=terms(query);if(terms.isEmpty())return new Result(new ArrayList<>(),terms,0);StringBuilder match=new StringBuilder();for(String t:terms){if(match.length()>0)match.append(" OR ");match.append('"').append(t.replace("\"","")).append('"');}
        PriorityQueue<Hit> best=new PriorityQueue<>(Comparator.comparingDouble((Hit h)->h.score).thenComparingLong(h->h.created));int total=0;String exact=query.trim().toLowerCase(Locale.ROOT);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT p.source,p.title,p.original,p.kind,p.unit,s.created,p.start_ms,p.end_ms,p.timed,s.revision FROM passages p JOIN sources s ON s.id=p.source WHERE passages MATCH ? AND s.created>=? AND s.created<?",new String[]{match.toString(),String.valueOf(start),String.valueOf(end)})){
            while(c.moveToNext()){if(Thread.currentThread().isInterrupted())throw new java.io.IOException("搜索已取消");String value=c.getString(2).toLowerCase(Locale.ROOT);double score=value.contains(exact)?100:0;for(String term:terms)if(value.contains(term))score+=Math.min(8,term.length());if(c.getString(3).equals("transcript"))score+=3;Hit h=new Hit(c,score);total++;if(best.size()<150)best.add(h);else if(score>best.peek().score||score==best.peek().score&&h.created>best.peek().created){best.poll();best.add(h);}}
        }
        List<Hit> hits=new ArrayList<>(best);hits.sort(Comparator.comparingLong((Hit h)->h.created).reversed().thenComparing(Comparator.comparingDouble((Hit h)->h.score).reversed()).thenComparingLong(h->h.start));return new Result(hits,terms,total);
    }
    static String excerpt(String value,List<String> terms){int hit=value.length();String lower=value.toLowerCase(Locale.ROOT);for(String t:terms){int at=lower.indexOf(t);if(at>=0)hit=Math.min(hit,at);}int start=Math.max(0,(hit==value.length()?0:hit)-45),end=Math.min(value.length(),start+210);return (start>0?"…":"")+value.substring(start,end)+(end<value.length()?"…":"");}
}
