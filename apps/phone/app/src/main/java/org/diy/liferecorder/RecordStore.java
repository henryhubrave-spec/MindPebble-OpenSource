package org.diy.liferecorder;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.media.MediaMetadataRetriever;
import java.io.*;
import java.util.*;
import org.json.*;
import org.diy.lifecore.LocalSecurity;

/** Phone-local domain database, intentionally separate from provider credentials. */
final class RecordStore extends SQLiteOpenHelper {
    final Context context;
    final File media;
    RecordStore(Context c){super(c,"life-v2.db",null,13);setWriteAheadLoggingEnabled(true);context=c.getApplicationContext();media=new File(c.getFilesDir(),"records");if(!media.isDirectory()&&!media.mkdirs()&&!media.isDirectory())throw new IllegalStateException("无法创建记录目录");}
    public void onConfigure(SQLiteDatabase db){db.execSQL("PRAGMA synchronous=FULL");db.setForeignKeyConstraintsEnabled(true);}
    public void onCreate(SQLiteDatabase db){
        WatchArchive.schema(db);
        db.execSQL("CREATE TABLE audio(id TEXT PRIMARY KEY,title TEXT NOT NULL,path TEXT NOT NULL,sha TEXT NOT NULL,bytes INTEGER NOT NULL,duration INTEGER NOT NULL,created INTEGER NOT NULL,metadata TEXT NOT NULL,transcript TEXT NOT NULL DEFAULT '',analysis TEXT NOT NULL DEFAULT '',vad_path TEXT NOT NULL DEFAULT '',vad_map TEXT NOT NULL DEFAULT '',source_retained INTEGER NOT NULL DEFAULT 1,retained_sha TEXT NOT NULL DEFAULT '',imported INTEGER NOT NULL DEFAULT 0,speech_json TEXT NOT NULL DEFAULT '',transcript_revision INTEGER NOT NULL DEFAULT 0,asset_deleted INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE facts(id TEXT PRIMARY KEY,text TEXT NOT NULL,source TEXT NOT NULL,state TEXT NOT NULL,created INTEGER NOT NULL,FOREIGN KEY(source) REFERENCES audio(id) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE tasks(id TEXT PRIMARY KEY,text TEXT NOT NULL,source TEXT,state TEXT NOT NULL,due INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL,FOREIGN KEY(source) REFERENCES audio(id) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY,source TEXT NOT NULL,type TEXT NOT NULL,input TEXT NOT NULL,status TEXT NOT NULL,result TEXT NOT NULL DEFAULT '',error TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL,stage TEXT NOT NULL DEFAULT '',progress_done INTEGER NOT NULL DEFAULT 0,progress_total INTEGER NOT NULL DEFAULT 0,progress_unit TEXT NOT NULL DEFAULT '',updated INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE tombstones(id TEXT PRIMARY KEY,created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE context(id TEXT PRIMARY KEY,source TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)");
        LifeMemory.schema(db);ContentFilter.schema(db);HourlySummaries.schema(db);WorkflowSchema.create(db);
    }
    public void onUpgrade(SQLiteDatabase db,int a,int b){
        if(a<2)db.execSQL("ALTER TABLE context ADD COLUMN source TEXT NOT NULL DEFAULT ''");
        if(a<3){db.execSQL("ALTER TABLE audio ADD COLUMN source_retained INTEGER NOT NULL DEFAULT 1");db.execSQL("ALTER TABLE audio ADD COLUMN retained_sha TEXT NOT NULL DEFAULT ''");}
        if(a<4){db.execSQL("ALTER TABLE audio ADD COLUMN imported INTEGER NOT NULL DEFAULT 0");db.execSQL("UPDATE audio SET imported=created");try(Cursor rows=db.rawQuery("SELECT id,metadata FROM audio",null)){while(rows.moveToNext()){try{long at=new JSONObject(rows.getString(1)).optLong("started_at_epoch_ms",0);if(at>0)db.execSQL("UPDATE audio SET created=? WHERE id=?",new Object[]{at,rows.getString(0)});}catch(Exception ignored){}}}}
        if(a<5){db.execSQL("ALTER TABLE audio ADD COLUMN speech_json TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE audio ADD COLUMN transcript_revision INTEGER NOT NULL DEFAULT 0");}
        if(a<6){db.execSQL("CREATE TABLE tasks_v6(id TEXT PRIMARY KEY,text TEXT NOT NULL,source TEXT,state TEXT NOT NULL,due INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL,FOREIGN KEY(source) REFERENCES audio(id) ON DELETE CASCADE)");db.execSQL("INSERT INTO tasks_v6 SELECT * FROM tasks");db.execSQL("DROP TABLE tasks");db.execSQL("ALTER TABLE tasks_v6 RENAME TO tasks");}
        if(a<7&&b>=7){db.execSQL("ALTER TABLE jobs ADD COLUMN stage TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE jobs ADD COLUMN progress_done INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE jobs ADD COLUMN progress_total INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE jobs ADD COLUMN progress_unit TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE jobs ADD COLUMN updated INTEGER NOT NULL DEFAULT 0");}
        if(a<8&&b>=8){db.execSQL("ALTER TABLE audio ADD COLUMN asset_deleted INTEGER NOT NULL DEFAULT 0");WatchArchive.schema(db);}
        if(a<9&&b>=9){LifeMemory.schema(db);try(Cursor c=db.rawQuery("SELECT id,text FROM facts WHERE state='accepted'",null)){while(c.moveToNext()){ContentValues v=new ContentValues();v.put("memory_id",c.getString(0));v.put("text",LifeMemory.tokens(c.getString(1)));db.insertOrThrow("memory_fts",null,v);}}}
        if(a<10&&b>=10)ContentFilter.schema(db);
        if(a<11&&b>=11)ContentFilter.claimsSchema(db);
        if(a<12&&b>=12)HourlySummaries.schema(db);
        if(a<13&&b>=13)WorkflowSchema.create(db);
        if(b>13)throw new IllegalStateException("需要兼容迁移，请保留数据库");
    }
    JSONArray rows(String sql,String... args)throws Exception{JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery(sql,args)){while(c.moveToNext()){JSONObject o=new JSONObject();for(int i=0;i<c.getColumnCount();i++){if(c.isNull(i))o.put(c.getColumnName(i),JSONObject.NULL);else if(c.getType(i)==Cursor.FIELD_TYPE_INTEGER)o.put(c.getColumnName(i),c.getLong(i));else o.put(c.getColumnName(i),c.getString(i));}a.put(o);}}return a;}
    JSONObject audio(String id)throws Exception{JSONArray a=rows("SELECT * FROM audio WHERE id=?",id);if(a.length()==0)throw new IOException("记录不存在或已删除");return a.getJSONObject(0);}
    boolean deleted(String id)throws Exception{return rows("SELECT id FROM tombstones WHERE id=?",id).length()>0;}
    void update(String table,String id,String key,String value){
        android.database.sqlite.SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{ContentValues v=new ContentValues();v.put(key,value);
        if(table.equals("audio")&&key.equals("transcript")){try{LifeMemory.history(this,id,"transcript",audio(id).optString("transcript"),value);LifeMemory.invalidate(this,id);}catch(Exception e){throw new IllegalStateException(e);}v.put("analysis","");v.put("speech_json","");db.execSQL("UPDATE audio SET transcript_revision=transcript_revision+1 WHERE id=?",new Object[]{id});}
        if(table.equals("facts")||table.equals("tasks")){try{JSONObject old=rows("SELECT * FROM "+table+" WHERE id=?",id).getJSONObject(0);LifeMemory.history(this,id,table+"."+key,old.optString(key),value);}catch(Exception e){throw new IllegalStateException(e);}}
        if(table.equals("life_events")){db.execSQL("UPDATE life_events SET revision=revision+1 WHERE id=?",new Object[]{id});db.execSQL("UPDATE life_reviews SET stale=1");}
        db.update(table,v,"id=?",new String[]{id});
        if(table.equals("facts")){try{LifeMemory.index(this,id);}catch(Exception e){throw new IllegalStateException(e);}}
        if(table.equals("facts")||table.equals("tasks"))db.execSQL("UPDATE life_reviews SET stale=1");
        if(table.equals("audio")&&key.equals("transcript")){db.execSQL("UPDATE facts SET state='review' WHERE source=? AND state<>'inactive'",new Object[]{id});db.execSQL("UPDATE tasks SET state='review' WHERE source=? AND state<>'done'",new Object[]{id});}
        db.setTransactionSuccessful();}finally{db.endTransaction();}
        if(table.equals("audio"))HourlyAutomation.schedule(context,true);
    }
    void editSpeech(String id,long expectedRevision,JSONObject speech)throws Exception{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{JSONObject current=audio(id);if(current.optLong("transcript_revision")!=expectedRevision)throw new IOException("原文已更新，请刷新");LifeMemory.history(this,id,"speech",current.optString("speech_json"),speech.toString());LifeMemory.invalidate(this,id);ContentValues v=new ContentValues();v.put("transcript",speech.getString("text"));v.put("speech_json",speech.toString());v.put("transcript_revision",expectedRevision+1);v.put("analysis","");db.update("audio",v,"id=?",new String[]{id});
            db.execSQL("UPDATE facts SET state='review' WHERE source=? AND state<>'inactive'",new Object[]{id});db.execSQL("UPDATE tasks SET state='review' WHERE source=? AND state<>'done'",new Object[]{id});db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        HourlyAutomation.schedule(context,true);
    }
    void updateDue(String id,long due){ContentValues v=new ContentValues();v.put("due",due);getWritableDatabase().update("tasks",v,"id=?",new String[]{id});getWritableDatabase().execSQL("UPDATE life_reviews SET stale=1");}
    /** Validates received bytes before database insertion. Existing IDs may never change content. */
    String receive(File temp,String id,String title,String sha,String metadata)throws Exception{
        SQLiteDatabase transaction=getWritableDatabase();transaction.beginTransaction();
        try {
        if(deleted(id)){transaction.setTransactionSuccessful();return "GONE";}
        JSONArray existing=rows("SELECT * FROM audio WHERE id=?",id);
        if(existing.length()>0){
            JSONObject row=existing.getJSONObject(0);if(!row.getString("sha").equals(sha))throw new IOException("片段身份冲突");if(row.optInt("asset_deleted")==1){transaction.setTransactionSuccessful();return "SPEECH_ONLY";}
            File current=new File(row.getString("path"));
            if(row.getInt("source_retained")==0){
                boolean silence=new JSONObject(row.getString("vad_map")).optBoolean("no_speech_detected");
                if(silence||(current.isFile()&&LocalSecurity.hash(current).equals(row.getString("retained_sha")))){transaction.setTransactionSuccessful();return "SPEECH_ONLY";}
                current=new File(media,id+".m4a");if(!temp.renameTo(current))throw new IOException("副本修复失败");LocalSecurity.syncDirectory(media);
                ContentValues repair=new ContentValues();repair.put("path",current.getPath());repair.put("bytes",current.length());repair.put("source_retained",1);repair.put("vad_path","");repair.put("vad_map","");repair.put("retained_sha","");transaction.update("audio",repair,"id=?",new String[]{id});
            }else if(!current.isFile()||!LocalSecurity.hash(current).equals(sha)){
                try(FileOutputStream out=new FileOutputStream(temp,true)){out.getFD().sync();}
                if(!temp.renameTo(current))throw new IOException("本地副本修复失败");LocalSecurity.syncDirectory(media);
            }
            transaction.setTransactionSuccessful();return "DURABLE";
        }
        if(!id.matches("[0-9a-f-]{36}")||!LocalSecurity.hash(temp).equals(sha))throw new IOException("文件校验失败");
        MediaMetadataRetriever r=new MediaMetadataRetriever();long duration;
        try{r.setDataSource(temp.getPath());if(!"yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)))throw new IOException("缺少音轨");duration=Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));}finally{r.release();}
        if(duration<=0)throw new IOException("音频为空");
        File dest=new File(media,id+".m4a");
        try(FileOutputStream s=new FileOutputStream(temp,true)){s.getFD().sync();}
        if(!temp.renameTo(dest))throw new IOException("文件提交失败");LocalSecurity.syncDirectory(media);
        ContentValues v=new ContentValues();v.put("id",id);v.put("title",title);v.put("path",dest.getPath());v.put("sha",sha);v.put("bytes",dest.length());v.put("duration",duration);long imported=System.currentTimeMillis();long at=new JSONObject(metadata).optLong("started_at_epoch_ms",imported);v.put("created",at>0?at:imported);v.put("imported",imported);v.put("metadata",metadata);
        getWritableDatabase().insertOrThrow("audio",null,v);transaction.setTransactionSuccessful();return "DURABLE";
        } finally {transaction.endTransaction();}
    }
    void retainSpeechOnly(String id)throws Exception {
        SQLiteDatabase db=getWritableDatabase();String original;
        db.beginTransaction();try{
            JSONObject a=audio(id);if(a.getInt("source_retained")==0)return;
            JSONObject map=new JSONObject(a.getString("vad_map"));String kept=a.getString("vad_path");
            boolean silence=map.optBoolean("no_speech_detected");File speech=new File(kept);
            if(!silence&&(!speech.isFile()||map.getJSONArray("ranges").length()==0))throw new IOException("请先完成 VAD 并试听");
            original=a.getString("path");ContentValues values=new ContentValues();values.put("source_retained",0);values.put("path",silence?"":kept);values.put("bytes",silence?0:speech.length());values.put("retained_sha",silence?"":LocalSecurity.hash(speech));db.update("audio",values,"id=?",new String[]{id});queueFileDeletion(original);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        retryFileDeletes();if(!original.isEmpty()&&new File(original).exists())throw new IOException("精简版已保存，原文件清理已排队重试");
        LocalSecurity.syncDirectory(media);
    }
    String importUri(android.net.Uri uri)throws Exception{
        File temp=File.createTempFile("import-",".part",media);String id=UUID.randomUUID().toString();String title="导入的录音";
        try(Cursor c=context.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())title=c.getString(0);}
        try{try(InputStream in=context.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(temp)){if(in==null)throw new IOException("无法读取文件");byte[] b=new byte[65536];int n;long total=0;while((n=in.read(b))!=-1){total+=n;if(total>512L*1024*1024||media.getUsableSpace()<64L*1024*1024)throw new IOException("文件过大或空间不足");out.write(b,0,n);}out.getFD().sync();}receive(temp,id,title==null?"录音":title,LocalSecurity.hash(temp),"{}");return id;}finally{temp.delete();}
    }
    String addJob(String source,String type,JSONObject input)throws Exception{
        if(type.equals("analysis_batch")){
            JSONArray ids=input.getJSONArray("sources");if(ids.length()==0||ids.length()>1000)throw new IOException("请选择 1–1000 段录音");
            java.util.ArrayList<JSONObject> ordered=new java.util.ArrayList<>();java.util.HashSet<String> seen=new java.util.HashSet<>();
            for(int i=0;i<ids.length();i++){String id=ids.getString(i);if(!seen.add(id))throw new IOException("来源重复");JSONObject a=audio(id);if(TranscriptDocument.units(a).length()>0)ordered.add(a);}
            ordered.sort((a,b)->Long.compare(a.optLong("created"),b.optLong("created")));
            if(ordered.isEmpty())throw new IOException("尚无有效原文，请先完成转写，再进行 AI 分析");
            String first="";getWritableDatabase().beginTransaction();try{
                JSONArray group=new JSONArray();
                if(input.optBoolean("automatic")){java.util.Iterator<JSONObject> pending=ordered.iterator();while(pending.hasNext()){JSONObject row=pending.next();if(rows("SELECT source FROM analysis_claims WHERE source=? AND revision=?",row.getString("id"),String.valueOf(row.optLong("transcript_revision"))).length()>0)pending.remove();}}
                for(JSONObject a:ordered){
                    JSONArray trial=new JSONArray(group.toString()).put(a.getString("id"));boolean flush=group.length()>=8;
                    if(!flush)try{AnalysisPipeline.snapshot(this,trial);}catch(IOException tooLarge){if(group.length()==0)throw tooLarge;flush=true;}
                    if(flush){String next=addAnalysisGroup(group,input);if(first.isEmpty())first=next;group=new JSONArray();}
                    group.put(a.getString("id"));
                }
                if(group.length()>0){String next=addAnalysisGroup(group,input);if(first.isEmpty())first=next;}
                getWritableDatabase().setTransactionSuccessful();return first;
            }finally{getWritableDatabase().endTransaction();}
        }
        if(type.equals("local_asr")){input.put("language",AppLanguage.code(context));if(!input.optBoolean("speaker_only")&&AppLanguage.code(context).equals("en")){if(!new OfflinePacks(context).ready("standard"))throw new IOException("英文离线识别需要下载并验证标准语音包");input.put("pack","standard");}}
        if(!source.isEmpty())audio(source);
        if(type.equals("asr")||type.equals("local_asr")){JSONObject audio=audio(source);if(audio.getString("path").isEmpty())throw new IOException("没有可转写音频");if(type.equals("local_asr")&&input.optBoolean("speaker_only")){JSONArray parts=AudioReview.object(audio.optString("speech_json")).optJSONArray("segments");if(parts==null||parts.length()==0)throw new IOException("请先完成逐段转写，再重新匹配声纹");}input.put("transcript_revision",audio.optLong("transcript_revision")).put("transcript_before",audio.optString("transcript"));input.put("asset_path",audio.getString("path")).put("asset_sha",audio.getInt("source_retained")==0?audio.getString("retained_sha"):audio.getString("sha"));}
        if(type.equals("analysis")){
            JSONArray ids=input.optJSONArray("sources");if(ids==null)ids=new JSONArray().put(source);if(!ids.getString(0).equals(source))throw new IOException("分析来源不一致");
            JSONObject snapshot=AnalysisPipeline.snapshot(this,ids);JSONObject memory=LifeMemory.context(this,snapshot);memory.put("boundary_context",AnalysisPipeline.boundaryContext(this,snapshot));input.put("audio_quality",AudioReview.evidence(audio(source)));input.put("analysis_snapshot",snapshot).put("memory_context",memory).put("understanding_mode",context.getSharedPreferences("understanding",0).getString("analysis_mode","daily"));
        }
        if(type.equals("life_review"))input.put("review_snapshot",LifeReview.snapshot(this,input.getLong("start"),input.getLong("end")));
        if(type.equals("answer")||type.equals("review")){
            JSONArray sources=input.getJSONArray("sources"),snapshots=new JSONArray();if(sources.length()>30)throw new IOException("来源过多");
            for(int n=0;n<sources.length();n++){JSONObject a=audio(sources.getString(n));snapshots.put(new JSONObject().put("source_id",a.getString("id")).put("title",a.getString("title")).put("transcript",a.getString("transcript")).put("timed_transcript",TranscriptDocument.units(a)).put("structured_facts",AudioReview.object(a.optString("analysis")).optJSONArray("facts")).put("accepted_memories",LifeMemory.enabled(this)?rows("SELECT id,text FROM facts WHERE source=? AND state='accepted' ORDER BY id",a.getString("id")):new JSONArray()));}
            input.put("evidence_snapshot",snapshots).put("memory_enabled",LifeMemory.enabled(this));
            if(input.optBoolean("citation_request"))SourceAnswer.prepare(this,input);
        }
        String id=UUID.randomUUID().toString();ContentValues v=new ContentValues();v.put("id",id);v.put("source",source);v.put("type",type);v.put("input",input.toString());v.put("status","queued");v.put("automatic",input.optBoolean("automatic")?1:0);v.put("created",System.currentTimeMillis());getWritableDatabase().beginTransaction();try{getWritableDatabase().insertOrThrow("jobs",null,v);if(type.equals("analysis")){JSONArray sources=input.getJSONObject("analysis_snapshot").getJSONArray("sources");for(int i=0;i<sources.length();i++){JSONObject row=sources.getJSONObject(i);getWritableDatabase().execSQL("INSERT OR REPLACE INTO analysis_claims(source,revision,job_id) VALUES(?,?,?)",new Object[]{row.getString("source_id"),row.optLong("transcript_revision"),id});}}getWritableDatabase().setTransactionSuccessful();return id;}finally{getWritableDatabase().endTransaction();}
    }
    private String addAnalysisGroup(JSONArray ids,JSONObject input)throws Exception{
        String source=ids.getString(0);return addJob(source,"analysis",new JSONObject().put("provider",input.getJSONObject("provider")).put("sources",ids).put("automatic",input.optBoolean("automatic",false)).put("transcript",audio(source).optString("transcript")));
    }
    void addManualTask(String text)throws Exception{if(text.trim().isEmpty())throw new IOException("请填写待办内容");ContentValues v=new ContentValues();v.put("id",UUID.randomUUID().toString());v.put("text",text.trim());v.putNull("source");v.put("state","accepted");v.put("created",System.currentTimeMillis());getWritableDatabase().insertOrThrow("tasks",null,v);}
    void addCandidate(String table,String source,String text)throws Exception{
        if(!(table.equals("facts")||table.equals("tasks")))throw new IllegalArgumentException();audio(source);if(text.trim().isEmpty())return;ContentValues v=new ContentValues();v.put("id",UUID.randomUUID().toString());v.put("text",text);v.put("source",source);v.put("state","review");v.put("created",System.currentTimeMillis());getWritableDatabase().insertOrThrow(table,null,v);
    }
    void forget(String id)throws Exception{
        JSONObject a=audio(id);JSONArray reminders=rows("SELECT id FROM tasks WHERE source=?",id);for(int i=0;i<reminders.length();i++)cancelTaskReminder(reminders.getJSONObject(i).getString("id"));SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            db.execSQL("DELETE FROM memory_fts WHERE memory_id IN (SELECT id FROM facts WHERE source=?)",new Object[]{id});
            db.delete("life_reviews","evidence LIKE ?",new String[]{"%"+id+"%"});
            queueAudioDeletion(a);
            db.execSQL("DELETE FROM correction_history WHERE target=? OR target IN (SELECT id FROM facts WHERE source=?) OR target IN (SELECT id FROM tasks WHERE source=?) OR target IN (SELECT event_id FROM event_units WHERE source=?)",new Object[]{id,id,id,id});
            db.execSQL("UPDATE audio SET analysis='' WHERE analysis LIKE ?",new Object[]{"%"+id+"%"});
            db.execSQL("DELETE FROM life_events WHERE id IN (SELECT event_id FROM event_units WHERE source=?)",new Object[]{id});
            ContentValues tomb=new ContentValues();tomb.put("id",id);tomb.put("created",System.currentTimeMillis());db.insertWithOnConflict("tombstones",null,tomb,SQLiteDatabase.CONFLICT_REPLACE);
            db.delete("jobs","source=? OR input LIKE ?",new String[]{id,"%"+id+"%"});db.delete("context","source=?",new String[]{id});db.delete("audio","id=?",new String[]{id});db.execSQL("DELETE FROM life_events WHERE NOT EXISTS(SELECT 1 FROM event_units WHERE event_id=life_events.id)");db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        retryFileDeletes();RecordSearch.forget(context,id);
    }
    private void cancelTaskReminder(String id){android.app.PendingIntent p=android.app.PendingIntent.getBroadcast(context,id.hashCode(),new Intent(context,ReminderReceiver.class).setAction("task:"+id).putExtra("id",id),android.app.PendingIntent.FLAG_IMMUTABLE|android.app.PendingIntent.FLAG_NO_CREATE);if(p!=null){((android.app.AlarmManager)context.getSystemService(Context.ALARM_SERVICE)).cancel(p);p.cancel();}((android.app.NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(id.hashCode());}
    void deleteTask(String id){cancelTaskReminder(id);getWritableDatabase().delete("tasks","id=?",new String[]{id});getWritableDatabase().execSQL("UPDATE life_reviews SET stale=1");}
    void releaseAudio(String id)throws Exception{JSONObject a=audio(id);getWritableDatabase().beginTransaction();try{queueAudioDeletion(a);getWritableDatabase().execSQL("UPDATE jobs SET status='cancelled',error='音频已由你删除' WHERE source=? AND status IN ('queued','running')",new Object[]{id});ContentValues v=new ContentValues();v.put("path","");v.put("vad_path","");v.put("asset_deleted",1);v.put("bytes",0);getWritableDatabase().update("audio",v,"id=?",new String[]{id});getWritableDatabase().setTransactionSuccessful();}finally{getWritableDatabase().endTransaction();}retryFileDeletes();}
    JSONArray search(String query)throws Exception{return rows("SELECT * FROM audio WHERE title LIKE ? OR transcript LIKE ? OR speech_json LIKE ? OR id IN (SELECT source FROM facts WHERE state='accepted' AND text LIKE ?) ORDER BY created DESC LIMIT 30","%"+query+"%","%"+query+"%","%"+query+"%","%"+query+"%");}
    void recoverJobs()throws Exception{
        getWritableDatabase().execSQL("UPDATE jobs SET status='uncertain',error='进程曾中断；请确认后重试，模型可能已收到请求' WHERE status='running' AND type<>'local_asr'");
        getWritableDatabase().execSQL("UPDATE jobs SET status='queued',error='本地任务中断，等待恢复' WHERE status='running' AND type='local_asr'");
    }
    private void queueFileDeletion(String path){
        if(path.isEmpty())return;ContentValues value=new ContentValues();value.put("path",path);
        getWritableDatabase().insertWithOnConflict("pending_file_deletes",null,value,SQLiteDatabase.CONFLICT_IGNORE);
    }
    private void queueAudioDeletion(JSONObject audio){
        for(String key:new String[]{"path","vad_path"})queueFileDeletion(audio.optString(key));
    }
    void retryFileDeletes()throws Exception{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            JSONArray pending=rows("SELECT path FROM pending_file_deletes");
            for(int i=0;i<pending.length();i++){String path=pending.getJSONObject(i).getString("path");File file=new File(path);
                if(!file.getCanonicalFile().getParentFile().equals(media.getCanonicalFile()))continue;
                if(rows("SELECT id FROM audio WHERE path=? OR vad_path=? LIMIT 1",path,path).length()>0)continue;
                if(!file.exists()||file.delete())db.delete("pending_file_deletes","path=?",new String[]{path});
            }LocalSecurity.syncDirectory(media);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    void recoverFiles()throws Exception{
        retryFileDeletes();
        Set<String> referenced=new HashSet<>();JSONArray a=rows("SELECT path,vad_path FROM audio");for(int i=0;i<a.length();i++){referenced.add(a.getJSONObject(i).getString("path"));referenced.add(a.getJSONObject(i).optString("vad_path"));}
        // Only our own deterministic scratch/orphan files; never user source files.
        File[] files=media.listFiles();if(files!=null)for(File f:files)if(!referenced.contains(f.getPath())&&f.lastModified()<System.currentTimeMillis()-86400000L)f.delete();
    }
}
