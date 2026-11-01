package org.diy.liferecorder;

import android.app.Instrumentation;
import android.content.*;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;

/** Isolated regression coverage for authorization, deletion, migration, index and recovery. */
public final class WorkflowTests extends Instrumentation {
    private int checks;
    private interface Action {void run()throws Exception;}
    private void check(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
    private void rejects(Action action,String reason)throws Exception{boolean rejected=false;try{action.run();}catch(Exception expected){rejected=true;}check(rejected,reason);}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){
        Bundle result=new Bundle();File root=new File(getTargetContext().getCacheDir(),"workflow-"+UUID.randomUUID());
        try{
            if(!android.os.Build.HARDWARE.matches("ranchu|goldfish"))throw new IOException("Disposable emulator required");
            root.mkdirs();run(isolated(new File(root,"source")));migration();ui();
            result.putInt("checks",checks);result.putString("status","passed");finish(-1,result);
        }catch(Throwable error){result.putString("error",android.util.Log.getStackTraceString(error));finish(1,result);}
        finally{remove(root);}
    }
    private Context isolated(File root){
        root.mkdirs();String prefix=root.getName()+UUID.randomUUID();return new ContextWrapper(getTargetContext()){
            public Context getApplicationContext(){return this;}
            public File getFilesDir(){return root;}
            public File getDatabasePath(String name){return new File(root,name);}
            public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(prefix+name,mode);}
            public Object getSystemService(String name){return name.equals(Context.JOB_SCHEDULER_SERVICE)?null:super.getSystemService(name);}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory f){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),f);}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory f,DatabaseErrorHandler h){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),f,h);}
        };
    }
    private String seed(RecordStore db,String text,long time)throws Exception{
        File wav=new File(db.context.getFilesDir(),UUID.randomUUID()+".wav");ByteBuffer bytes=ByteBuffer.allocate(32044).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(new byte[]{82,73,70,70}).putInt(32036).put(new byte[]{87,65,86,69,102,109,116,32}).putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put(new byte[]{100,97,116,97}).putInt(32000);
        try(FileOutputStream out=new FileOutputStream(wav)){out.write(bytes.array());}
        String id;try{id=db.importUri(Uri.fromFile(wav));}finally{wav.delete();}
        db.getWritableDatabase().execSQL("UPDATE audio SET created=? WHERE id=?",new Object[]{time,id});
        db.editSpeech(id,0,new JSONObject().put("text",text).put("segments",new JSONArray().put(new JSONObject().put("text",text).put("start_ms",0).put("end_ms",900).put("speaker","说话人 1"))));return id;
    }
    private void run(Context c)throws Exception{
        try(RecordStore db=new RecordStore(c);RecordSearch index=new RecordSearch(c)){
            long now=System.currentTimeMillis();String before=seed(db,"边界旧文字",now-2000),source=seed(db,"独特青松方案",now),other=seed(db,"独特琥珀方案",now+2000);
            JSONObject provider=new JSONObject().put("endpoint","https://example.invalid/v1").put("model","test").put("revision",1);
            c.getSharedPreferences("understanding",0).edit().putBoolean("auto_events",true).putString("auto_provider",UnderstandingAutomation.binding(provider)).commit();
            String automatic=db.addJob(source,"analysis",new JSONObject().put("provider",provider).put("transcript",db.audio(source).getString("transcript")).put("automatic",true));
            String manual=db.addJob(other,"analysis",new JSONObject().put("provider",provider).put("transcript",db.audio(other).getString("transcript")));
            JSONObject input=new JSONObject(db.rows("SELECT input FROM jobs WHERE id=?",automatic).getJSONObject(0).getString("input"));
            String frozen=input.getJSONObject("memory_context").toString();
            check(input.getJSONObject("memory_context").getJSONArray("boundary_context").length()>0,"boundary evidence is frozen when queued");
            check(input.has("audio_quality"),"quality evidence is frozen");
            db.update("audio",before,"transcript","边界已更新");
            check(input.getJSONObject("memory_context").toString().equals(frozen),"later edits do not expand frozen context");
            rejects(()->LifeMemory.checkContext(db,input.getJSONObject("memory_context")),"stale boundary evidence is rejected");
            UnderstandingAutomation.disable(c);
            check(db.rows("SELECT status FROM jobs WHERE id=?",automatic).getJSONObject(0).getString("status").equals("cancelled"),"disable cancels automatic queue");
            check(db.rows("SELECT status FROM jobs WHERE id=?",manual).getJSONObject(0).getString("status").equals("queued"),"disable preserves explicit manual work");
            check(UnderstandingAutomation.cancelled(c,input),"request guard observes revoked consent");
            check(!UnderstandingAutomation.cancelled(c,new JSONObject(input.toString()).put("automatic",false)),"manual request is independent of automation");
            try(java.net.ServerSocket server=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){
                SecureSettings settings=new SecureSettings(c);settings.saveProvider("workflow-guard","http://127.0.0.1:"+server.getLocalPort()+"/v1","synthetic","",false);
                c.getSharedPreferences("network-policy",0).edit().putBoolean("allow_local_http",true).commit();
                ProviderClient client=new ProviderClient(c);JSONObject config=client.snapshot("workflow-guard");JSONObject automaticInput=new JSONObject().put("automatic",true).put("provider",config);
                rejects(()->client.chat(config,"test","synthetic",()->UnderstandingAutomation.cancelled(c,automaticInput)),"revoked request is rejected at provider boundary");
                server.setSoTimeout(150);boolean empty=false;try(java.net.Socket ignored=server.accept()){}catch(java.net.SocketTimeoutException expected){empty=true;}
                check(empty,"revoked permission sends no connection to provider");
            }
            index.sync(db,null);check(index.find("青松",0,Long.MAX_VALUE).total>0,"index builds");
            long cursor=index.getReadableDatabase().compileStatement("SELECT version FROM sync_state WHERE id=1").simpleQueryForLong();
            index.sync(db,null);check(cursor==index.getReadableDatabase().compileStatement("SELECT version FROM sync_state WHERE id=1").simpleQueryForLong(),"unchanged search does not rescan sources");
            db.addCandidate("facts",source,"北极海豚记忆");String fact=db.rows("SELECT id FROM facts WHERE source=?",source).getJSONObject(0).getString("id");db.update("facts",fact,"state","accepted");
            index.sync(db,null);check(index.find("海豚",0,Long.MAX_VALUE).total>0,"accepted memory updates index incrementally");
            db.update("facts",fact,"state","inactive");index.sync(db,null);check(index.find("海豚",0,Long.MAX_VALUE).total==0,"inactive memory leaves index");
            db.addCandidate("tasks",source,"私人待办");String task=db.rows("SELECT id FROM tasks WHERE source=?",source).getJSONObject(0).getString("id");db.update("tasks",task,"text","私人修订");
            review(db,"affected",source);review(db,"unrelated",other);
            String path=db.audio(source).getString("path");db.forget(source);
            check(db.rows("SELECT id FROM correction_history WHERE target=?",task).length()==0,"forget removes task correction text");
            check(db.rows("SELECT id FROM tasks WHERE source=?",source).length()==0,"forget removes tasks");
            check(db.rows("SELECT id FROM life_reviews WHERE id='affected'").length()==0,"forget removes affected review");
            check(db.rows("SELECT stale FROM life_reviews WHERE id='unrelated'").getJSONObject(0).optInt("stale")==0,"unrelated review is retained and fresh");
            check(!new File(path).exists()&&db.rows("SELECT path FROM pending_file_deletes").length()==0,"audio cleanup is committed and acknowledged");
            index.sync(db,null);check(index.find("青松",0,Long.MAX_VALUE).total==0,"deleted source leaves index");
            ByteArrayOutputStream backup=new ByteArrayOutputStream();BackupArchive.write(db,backup);byte[] bytes=backup.toByteArray();
            Context target=isolated(new File(c.getFilesDir().getParentFile(),"target"));
            try(RecordStore restored=new RecordStore(target);BackupArchive.Prepared prepared=BackupArchive.prepare(c.getFilesDir(),new ByteArrayInputStream(bytes))){
                check(prepared.recordings()==2,"full archive preview counts sources");BackupArchive.restore(restored,prepared);
                check(restored.rows("SELECT id FROM audio").length()==2,"full backup roundtrip restores recordings");
                check(restored.audio(other).getString("transcript").equals(db.audio(other).getString("transcript")),"roundtrip preserves transcript");
                check(new File(restored.audio(other).getString("path")).isFile(),"restored paths point to imported files");
                check(restored.rows("SELECT id FROM jobs").length()==0,"restore never recreates cloud actions");
                check(restored.deleted(source),"restore preserves deletion tombstones");
                check(!target.getSharedPreferences("understanding",0).getBoolean("auto_events",true),"restore disables automatic upload");
                rejects(()->BackupArchive.restore(restored,prepared),"nonempty library cannot be overwritten");
            }
            rejects(()->BackupArchive.prepare(c.getFilesDir(),new ByteArrayInputStream(damage(bytes))),"corrupted audio hash rejected");
            String cleanup=seed(db,"清理失败重试",now+4000);File blocked=new File(db.media,"blocked-audio");blocked.mkdirs();File child=new File(blocked,"child");try(FileOutputStream out=new FileOutputStream(child)){out.write(1);}
            String original=db.audio(cleanup).getString("path");db.update("audio",cleanup,"path",blocked.getPath());db.forget(cleanup);
            check(db.rows("SELECT path FROM pending_file_deletes WHERE path=?",blocked.getPath()).length()==1,"failed file deletion remains durable");child.delete();db.retryFileDeletes();check(!blocked.exists(),"failed file deletion retries successfully");new File(original).delete();
            byte[] unsafe=archive("../escape","bad");rejects(()->BackupArchive.prepare(c.getFilesDir(),new ByteArrayInputStream(unsafe)),"path traversal rejected");
            rejects(()->BackupArchive.prepare(c.getFilesDir(),new ByteArrayInputStream(archive("records.json","{\"backup_version\":2}"))),"legacy export rejected explicitly");
            try(BackupArchive.Prepared prepared=BackupArchive.prepare(c.getFilesDir(),new ByteArrayInputStream(bytes))){
                prepared.manifest.getJSONArray("audio").getJSONObject(0).put("unexpected_column","invalid");
                Context rollback=isolated(new File(c.getFilesDir().getParentFile(),"rollback"));
                try(RecordStore restored=new RecordStore(rollback)){
                    rejects(()->BackupArchive.restore(restored,prepared),"invalid field fails restoration");
                    check(restored.rows("SELECT id FROM audio").length()==0,"failed restoration rolls back records");
                    check(restored.media.listFiles().length==0,"failed restoration removes copied audio");
                }
            }
        }
    }
    private void ui()throws Exception{
        BackupActivity backup=(BackupActivity)startActivitySync(new Intent(getTargetContext(),BackupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        final String[] text={""};runOnMainSync(()->text[0]=UnderstandingTests.text(backup.body));
        check(text[0].contains("保存完整备份")&&text[0].contains("选择备份并检查"),"backup screen exposes both actions");
        check(text[0].contains("空资料库")&&text[0].contains("ZIP 未加密"),"recovery boundaries are visible before selection");
        runOnMainSync(backup::finish);
        TaskCenterActivity tasks=(TaskCenterActivity)startActivitySync(new Intent(getTargetContext(),TaskCenterActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long until=android.os.SystemClock.elapsedRealtime()+5000;
        do{Thread.sleep(100);runOnMainSync(()->text[0]=UnderstandingTests.text(tasks.body));}while(!text[0].contains("已完成")&&android.os.SystemClock.elapsedRealtime()<until);
        check(text[0].contains("已完成"),"task center applies asynchronous query result");runOnMainSync(tasks::finish);
    }
    private void migration()throws Exception{
        try(SQLiteDatabase sql=SQLiteDatabase.create(null)){
            sql.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY,input TEXT,status TEXT,type TEXT,created INTEGER,source TEXT)");
            sql.execSQL("CREATE TABLE audio(id TEXT PRIMARY KEY,title TEXT,created INTEGER,transcript TEXT,speech_json TEXT,transcript_revision INTEGER)");
            sql.execSQL("CREATE TABLE facts(id TEXT PRIMARY KEY,source TEXT,state TEXT)");sql.execSQL("CREATE TABLE tasks(id TEXT PRIMARY KEY,source TEXT)");
            sql.execSQL("INSERT INTO jobs VALUES('auto','{\"automatic\":true}','queued','analysis',0,'')");WorkflowSchema.create(sql);
            check(sql.compileStatement("SELECT automatic FROM jobs WHERE id='auto'").simpleQueryForLong()==1,"v12 migration parses automatic flag");
            sql.execSQL("INSERT INTO audio VALUES('source','title',1,'text','',0)");
            check(sql.compileStatement("SELECT count(*) FROM search_changes").simpleQueryForLong()==1,"migration installs search triggers");
        }
    }
    private static void review(RecordStore db,String id,String source)throws Exception{
        ContentValues row=new ContentValues();row.put("id",id);row.put("kind","daily");row.put("start",1);row.put("end",2);row.put("created",1);row.put("result","{}");row.put("evidence",new JSONObject().put("sources",new JSONArray().put(new JSONObject().put("source_id",source))).toString());db.getWritableDatabase().insertOrThrow("life_reviews",null,row);
    }
    private static byte[] damage(byte[] source)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();boolean changed=false;
        try(ZipInputStream input=new ZipInputStream(new ByteArrayInputStream(source));ZipOutputStream zip=new ZipOutputStream(out)){
            ZipEntry entry;byte[] buffer=new byte[4096];while((entry=input.getNextEntry())!=null){zip.putNextEntry(new ZipEntry(entry.getName()));int n;while((n=input.read(buffer))!=-1){if(!changed&&entry.getName().startsWith("assets/")&&n>0){buffer[0]^=1;changed=true;}zip.write(buffer,0,n);}zip.closeEntry();}
        }return out.toByteArray();
    }
    private static byte[] archive(String name,String content)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry(name));zip.write(content.getBytes("UTF-8"));zip.closeEntry();}return bytes.toByteArray();}
    private static void remove(File root){File[] files=root.listFiles();if(files!=null)for(File file:files)remove(file);root.delete();}
}
