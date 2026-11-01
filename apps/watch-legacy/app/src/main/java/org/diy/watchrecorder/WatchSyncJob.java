package org.diy.watchrecorder;

import android.app.job.*;
import android.content.*;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;

/** One batched automatic schedule. Explicit sync bypasses the cadence guard. */
public final class WatchSyncJob extends JobService {
    static final int ID=8267;
    static final long INTERVAL_MS=30*60*1000L, FLEX_MS=5*60*1000L;
    private AtomicBoolean stopped=new AtomicBoolean();
    static int boot(Context c){return android.provider.Settings.Global.getInt(c.getContentResolver(),"boot_count",-1);}
    static boolean due(long now,long previous,boolean sameBoot){return due(now,previous,sameBoot,FLEX_MS);}
    static boolean due(long now,long previous,boolean sameBoot,long flex){return !sameBoot||previous<0||now<previous||now-previous>=INTERVAL_MS-Math.max(FLEX_MS,Math.min(INTERVAL_MS,flex));}
    static long effectiveFlex(Context c){JobInfo actual=((JobScheduler)c.getSystemService(JOB_SCHEDULER_SERVICE)).getPendingJob(ID);return actual==null?INTERVAL_MS/3:actual.getFlexMillis();}
    public static void schedule(Context c){
        if(!WatchLink.paired(c))return;
        JobScheduler scheduler=(JobScheduler)c.getSystemService(JOB_SCHEDULER_SERVICE);JobInfo existing=scheduler.getPendingJob(ID);
        // Re-registering on each saved segment would postpone the next batch indefinitely.
        if(existing!=null&&existing.isPeriodic()&&existing.getIntervalMillis()==INTERVAL_MS&&existing.isPersisted())return;
        android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putLong("planned_at_elapsed_ms",SystemClock.elapsedRealtime());
        JobInfo job=new JobInfo.Builder(ID,new ComponentName(c,WatchSyncJob.class)).setPeriodic(INTERVAL_MS,FLEX_MS)
            .setPersisted(true).setExtras(extras).setBackoffCriteria(INTERVAL_MS,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();scheduler.schedule(job);
    }
    public boolean onStartJob(JobParameters p){
        final AtomicBoolean stop=new AtomicBoolean();stopped=stop;
        new Thread(()->{try{synchronized(WatchLink.LOCK){
            if(stop.get()||!WatchLink.paired(this))return;
            SharedPreferences prefs=WatchLink.prefs(this);if(!due(SystemClock.elapsedRealtime(),prefs.getLong("attempt_elapsed",-1),prefs.getInt("attempt_boot",-2)==boot(this),effectiveFlex(this)))return;
            WatchLink.sync(this);
        }}catch(Exception e){CaptureDiagnostics.event(this,"sync_deferred",e.getClass().getSimpleName());}
        finally{if(!stop.get())jobFinished(p,false);}},"watch-batch-sync").start();return true;
    }
    public boolean onStopJob(JobParameters p){stopped.set(true);return false;}
}
