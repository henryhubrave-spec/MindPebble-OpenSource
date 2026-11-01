package org.diy.liferecorder;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.ContentValues;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Disposable emulator only. Uses an isolated database and synthetic files. */
public final class ExportTests extends Instrumentation {
    private final ArrayList<String> checks=new ArrayList<>();
    private void check(String name,boolean condition){if(!condition)throw new AssertionError(name);checks.add(name);}
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){Bundle result=new Bundle();File root=null;
        if(!android.os.Build.HARDWARE.equals("ranchu")&&!android.os.Build.HARDWARE.equals("goldfish")){result.putString("error","Disposable emulator required");finish(1,result);return;}
        try{
            root=new File(getTargetContext().getCacheDir(),"export-tests-"+UUID.randomUUID());if(!root.mkdirs())throw new IOException("test dir unavailable");final File isolated=root;
            Context isolatedContext=new ContextWrapper(getTargetContext()){
                @Override public Context getApplicationContext(){return this;}
                @Override public File getFilesDir(){return isolated;}
                @Override public File getDatabasePath(String name){return new File(isolated,name);}
                @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
                @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);}
            };
            try(RecordStore db=new RecordStore(isolatedContext)){
                long start=1700000000000L,end=start+86400000L;
                File original=new File(root,"sample.wav");try(FileOutputStream out=new FileOutputStream(original)){out.write(new byte[]{'R','I','F','F',1,2,3,4});}
                insert(db,"selected/../source",start+1000,original,"inside transcript",true);
                insert(db,"outside",end,original,"OUTSIDE_PRIVATE_TEXT",true);
                insert(db,"missing-original",start+2000,new File(root,"missing.wav"),"second transcript",false);
                JSONObject speech=new JSONObject().put("text","inside transcript").put("segments",new JSONArray().put(new JSONObject().put("text","inside transcript").put("speaker","本人").put("start_ms",1250).put("end_ms",2500)));
                db.getWritableDatabase().execSQL("UPDATE audio SET speech_json=? WHERE id=?",new Object[]{speech.toString(),"selected/../source"});
                JSONObject metadata=new JSONObject().put("api_key","SECRET_NOT_TO_EXPORT").put("markers",new JSONArray().put(new JSONObject().put("offset_ms",1200).put("note","remember this")).put(new JSONObject().put("offset_ms",5000).put("note","INVALID_END_MARKER")));
                db.getWritableDatabase().execSQL("UPDATE audio SET metadata=? WHERE id=?",new Object[]{metadata.toString(),"selected/../source"});
                LocalExport.Plan textPlan=LocalExport.prepare(db,new LocalExport.Options(start,end,LocalExport.TEXT,true),()->false);
                Map<String,byte[]> textFiles=unzip(write(textPlan));String allText=joined(textFiles);
                check("workflow contains timestamped transcript",allText.contains("00:00:01.250")&&allText.contains("inside transcript"));
                check("workflow excludes original audio",textFiles.keySet().stream().noneMatch(name->name.startsWith("audio/")));
                check("exclusive date boundary excludes next day",!allText.contains("OUTSIDE_PRIVATE_TEXT")&&!allText.contains("\"source_id\": \"outside\""));
                check("metadata secrets and internal paths are not exported",!allText.contains("SECRET_NOT_TO_EXPORT")&&!allText.contains(root.getAbsolutePath()));
                check("archive filenames cannot traverse directories",textFiles.keySet().stream().noneMatch(name->name.startsWith("/")||name.contains("..")));
                check("manual highlight contains source and offset",allText.contains("remember this")&&allText.contains("1200")&&allText.contains("selected/../source"));
                check("marker at recording end is excluded",!allText.contains("INVALID_END_MARKER"));
                check("empty selected types are reported as zero",allText.contains("\"daily_reviews\": 0")&&textPlan.counts[1]==2);
                check("selected export is not full backup",!textFiles.containsKey("records.json"));
                LocalExport.Plan one=LocalExport.prepare(db,new LocalExport.Options(start,end,LocalExport.TRANSCRIPT,false,"selected/../source"),()->false);
                check("single record scope excludes other same-day records",!joined(unzip(write(one))).contains("second transcript"));
                LocalExport.Plan highlights=LocalExport.prepare(db,new LocalExport.Options(start,end,LocalExport.HIGHLIGHTS,false,"selected/../source"),()->false);
                check("highlights alone do not include transcript",!joined(unzip(write(highlights))).contains("inside transcript"));
                LocalExport.Plan originalPlan=LocalExport.prepare(db,new LocalExport.Options(start,end,LocalExport.ORIGINAL,false,"selected/../source"),()->false);
                Map<String,byte[]> originalFiles=unzip(write(originalPlan));String audioName=originalFiles.keySet().stream().filter(name->name.startsWith("audio/")).findFirst().get();
                check("original audio bytes and extension preserved",audioName.endsWith(".wav")&&Arrays.equals(originalFiles.get(audioName),new byte[]{'R','I','F','F',1,2,3,4}));
                LocalExport.Plan mixed=LocalExport.prepare(db,new LocalExport.Options(start,end,LocalExport.ALL,false),()->false);
                check("missing original produces visible warning",mixed.warnings.stream().anyMatch(w->w.contains("missing-original")));
                boolean rejected=false;try{LocalExport.prepare(db,new LocalExport.Options(start,end,LocalExport.ORIGINAL,false,"missing-original"),()->false);}catch(IOException e){rejected=true;}
                check("unavailable only selection fails instead of empty success",rejected);
                rejected=false;try{new LocalExport.Options(start,end,0,false);}catch(IllegalArgumentException e){rejected=true;}check("empty content selection rejected",rejected);
                rejected=false;try{new LocalExport.Options(start,end,LocalExport.ORIGINAL,true);}catch(IllegalArgumentException e){rejected=true;}check("workflow rejects original audio",rejected);
                ByteArrayOutputStream cancelledBytes=new ByteArrayOutputStream();rejected=false;try{LocalExport.writeSelected(one,cancelledBytes,null,()->true);}catch(LocalExport.Cancelled e){rejected=true;}check("preflight cancellation writes nothing",rejected&&cancelledBytes.size()==0);
                final long[] progress={0};final boolean[] cancel={false};rejected=false;try{LocalExport.writeSelected(textPlan,new ByteArrayOutputStream(),(label,done,total)->{progress[0]=done;if(done>0)cancel[0]=true;},()->cancel[0]);}catch(LocalExport.Cancelled e){rejected=true;}check("mid-write cancellation stops export",rejected&&progress[0]>0);
                try(FileOutputStream out=new FileOutputStream(original,true)){out.write(9);}rejected=false;try{write(originalPlan);}catch(IOException e){rejected=true;}check("changed source file is rejected",rejected);
                JSONObject cross=new JSONObject().put("summary","CROSS_RANGE_PRIVATE_SUMMARY").put("sources",new JSONArray().put(new JSONObject().put("source_id","selected/../source")).put(new JSONObject().put("source_id","outside")));
                db.getWritableDatabase().execSQL("UPDATE audio SET analysis=? WHERE id=?",new Object[]{cross.toString(),"selected/../source"});
                LocalExport.Plan scoped=LocalExport.prepare(db,new LocalExport.Options(start,end,LocalExport.TEXT,true),()->false);check("cross-range summary skipped with warning",!joined(unzip(write(scoped))).contains("CROSS_RANGE_PRIVATE_SUMMARY")&&scoped.warnings.stream().anyMatch(w->w.contains("日期范围外")));
                addReview(db,start,end);LocalExport.Plan reviewed=LocalExport.prepare(db,new LocalExport.Options(start,end,LocalExport.DAILY,false),()->false);String reviewText=joined(unzip(write(reviewed)));
                check("daily review exports in-range claims with evidence",reviewText.contains("IN_RANGE_CLAIM")&&reviewText.contains("IN_RANGE_EVIDENCE"));
                check("daily-only export includes cited source timestamps",reviewed.sources.length()==1&&reviewed.sources.getJSONObject(0).optString("source_id").equals("selected/../source")&&reviewed.sources.getJSONObject(0).has("started_at"));
                check("daily review excludes cross-range claims and evidence",!reviewText.contains("OUTSIDE_REVIEW_CLAIM")&&!reviewText.contains("OUTSIDE_REVIEW_EVIDENCE"));
            }
            result.putStringArrayList("passed",checks);result.putInt("count",checks.size());finish(-1,result);
        }catch(Throwable error){result.putStringArrayList("passed",checks);result.putString("error",error.getClass().getSimpleName()+": "+error.getMessage());finish(1,result);}finally{if(root!=null)remove(root);}
    }
    private static void insert(RecordStore db,String id,long at,File file,String transcript,boolean retained){ContentValues row=new ContentValues();row.put("id",id);row.put("title","Synthetic "+id);row.put("created",at);row.put("duration",5000);row.put("path",file.getPath());row.put("bytes",file.length());row.put("sha","synthetic");row.put("metadata","{}");row.put("transcript",transcript);row.put("source_retained",retained?1:0);db.getWritableDatabase().insertOrThrow("audio",null,row);}
    private static void addReview(RecordStore db,long start,long end)throws Exception{
        JSONArray facts=new JSONArray().put(new JSONObject().put("id","in-fact").put("source","selected/../source").put("text","IN_RANGE_EVIDENCE")).put(new JSONObject().put("id","out-fact").put("source","outside").put("text","OUTSIDE_REVIEW_EVIDENCE"));
        JSONArray claims=new JSONArray().put(new JSONObject().put("section","achievement").put("kind","observed").put("text","IN_RANGE_CLAIM").put("evidence_ids",new JSONArray().put("in-fact"))).put(new JSONObject().put("section","achievement").put("kind","observed").put("text","OUTSIDE_REVIEW_CLAIM").put("evidence_ids",new JSONArray().put("out-fact")));
        ContentValues row=new ContentValues();row.put("id","review");row.put("kind","daily");row.put("start",start);row.put("end",end);row.put("created",end);row.put("result",new JSONObject().put("claims",claims).toString());row.put("evidence",new JSONObject().put("facts",facts).toString());db.getWritableDatabase().insertOrThrow("life_reviews",null,row);
    }
    private static byte[] write(LocalExport.Plan plan)throws Exception{ByteArrayOutputStream output=new ByteArrayOutputStream();LocalExport.writeSelected(plan,output,null,()->false);return output.toByteArray();}
    private static Map<String,byte[]> unzip(byte[] bytes)throws Exception{Map<String,byte[]> out=new LinkedHashMap<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){ByteArrayOutputStream value=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int size;while((size=zip.read(buffer))!=-1)value.write(buffer,0,size);out.put(entry.getName(),value.toByteArray());}}return out;}
    private static String joined(Map<String,byte[]> entries)throws Exception{StringBuilder out=new StringBuilder();for(Map.Entry<String,byte[]> entry:entries.entrySet())if(!entry.getKey().startsWith("audio/"))out.append(new String(entry.getValue(),"UTF-8"));return out.toString();}
    private static void remove(File path){File[] children=path.listFiles();if(children!=null)for(File child:children)remove(child);path.delete();}
}
