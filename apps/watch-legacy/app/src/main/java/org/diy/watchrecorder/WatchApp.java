package org.diy.watchrecorder;
/** Restore only the durable enabled session, never manual pause/stop. */
public final class WatchApp extends android.app.Application {
    public void onCreate(){super.onCreate();CaptureIntent.migrate(this);
        CaptureDiagnostics.event(this,"app_create","sdk="+android.os.Build.VERSION.SDK_INT+" mode="+CaptureIntent.mode(this));
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{try{CaptureDiagnostics.event(this,"uncaught_exception",thread.getName()+" "+error.getClass().getSimpleName());if(CaptureIntent.mode(this).equals(CaptureIntent.RUNNING))CaptureIntent.arm(this,CaptureIntent.epoch(this),10000);}catch(Throwable ignored){}if(previous!=null)previous.uncaughtException(thread,error);else android.os.Process.killProcess(android.os.Process.myPid());});
        new Thread(()->{try{RecordingRecovery.inspect(this);}catch(Exception e){CaptureDiagnostics.event(this,"file_recovery_failed",e.getClass().getSimpleName());}},"recording-recovery").start();
        // Give incoming pause/stop commands time to revoke an older recovery intent.
        new android.os.Handler(getMainLooper()).postDelayed(()->{if(CaptureIntent.mode(this).equals(CaptureIntent.RUNNING))CaptureIntent.kick(this,CaptureIntent.epoch(this),"app_restart");},1000);
        WatchSyncJob.schedule(this);
    }
}
