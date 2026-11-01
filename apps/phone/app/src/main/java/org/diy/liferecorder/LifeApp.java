package org.diy.liferecorder;
import android.app.Application;import java.io.*;import org.json.*;
public final class LifeApp extends Application {
 public void onCreate(){super.onCreate();final long enrollmentCutoff=System.currentTimeMillis();try(RecordStore db=new RecordStore(this)){db.recoverJobs();}catch(Exception ignored){}new Thread(()->{try(RecordStore db=new RecordStore(this)){
    db.recoverFiles();
    BackupArchive.recoverStaging(getCacheDir(),enrollmentCutoff-86400000L);
    File[] enrollmentTemps=getCacheDir().listFiles((d,name)->name.startsWith("enroll-recording-")||name.startsWith("voice-enrollment-"));if(enrollmentTemps!=null)for(File temp:enrollmentTemps)if(temp.lastModified()<enrollmentCutoff)temp.delete();
    OfflineScheduler.schedule(this);
    HourlyAutomation.schedule(this,true);
    ContextReminder.schedule(this);
    if(!getSharedPreferences("migration",0).getBoolean("v01",false)&&getDatabasePath("library.db").isFile()){
      try(LocalLibrary old=new LocalLibrary(this)){for(LocalLibrary.Audio a:old.list()){
        if(db.rows("SELECT id FROM audio WHERE id=?",a.id).length()>0||db.deleted(a.id))continue;
        File tmp=File.createTempFile("migration-",".part",db.media);try{try(InputStream in=new FileInputStream(a.path);OutputStream out=new FileOutputStream(tmp)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}db.receive(tmp,a.id,a.title,org.diy.lifecore.LocalSecurity.hash(tmp),"{}");}finally{tmp.delete();}
      }}getSharedPreferences("migration",0).edit().putBoolean("v01",true).commit();
    }
    JSONArray tasks=db.rows("SELECT id,due FROM tasks WHERE state='accepted' AND due>0");for(int i=0;i<tasks.length();i++){JSONObject t=tasks.getJSONObject(i);if(t.getLong("due")>System.currentTimeMillis())ReminderReceiver.schedule(this,t.getString("id"),t.getLong("due"));}
 }catch(Exception ignored){}},"local-startup-recovery").start();}
}
