package org.diy.watchrecorder;

import android.Manifest;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.SystemClock;

/** Durable user intent and epoch-checked recovery. A stale alarm can never undo pause/stop. */
final class CaptureIntent {
    static final String RUNNING="running",PAUSED="paused",OFF="off";
    static final String RECOVER="recover-recording";
    static final int JOB_ID=8268;
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("recorder",0);}
    static synchronized void migrate(Context c){
        SharedPreferences p=prefs(c);if(p.contains("desired_mode"))return;
        String old=p.getString("state","idle");String mode=old.equals("recording")?RUNNING:old.equals("paused")?PAUSED:OFF;
        if(!p.edit().putString("desired_mode",mode).putLong("capture_epoch",1).commit())throw new IllegalStateException("无法保存记录状态");
    }
    static String mode(Context c){migrate(c);return prefs(c).getString("desired_mode",OFF);}
    static long epoch(Context c){migrate(c);return prefs(c).getLong("capture_epoch",1);}
    static boolean wanted(Context c,long generation){return mode(c).equals(RUNNING)&&epoch(c)==generation;}
    static synchronized long user(Context c,String mode){
        migrate(c);long next=epoch(c)+1;
        if(!prefs(c).edit().putString("desired_mode",mode).putLong("capture_epoch",next).commit())throw new IllegalStateException("无法保存记录意图");
        if(!mode.equals(RUNNING))cancel(c);else arm(c,next,90000);
        CaptureDiagnostics.event(c,"user_intent",mode+" epoch="+next);return next;
    }
    static boolean permission(Context c){return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    static void kick(Context c,long generation,String reason){
        if(!wanted(c,generation))return;
        if(!permission(c)){CaptureDiagnostics.event(c,"resume_blocked","microphone permission unavailable");return;}
        try{c.startForegroundService(new Intent(c,RecordingService.class).setAction(RECOVER).putExtra("capture_epoch",generation).putExtra("resume_reason",reason));}
        catch(RuntimeException e){CaptureDiagnostics.event(c,"resume_start_failed",e.getClass().getSimpleName());arm(c,generation,60000);}
    }
    static synchronized void arm(Context c,long generation,long delay){
        if(!wanted(c,generation))return;
        PendingIntent pending=alarm(c,generation,PendingIntent.FLAG_UPDATE_CURRENT);
        ((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+Math.max(3000,delay),pending);
        JobScheduler jobs=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(jobs.getPendingJob(JOB_ID)==null)jobs.schedule(new JobInfo.Builder(JOB_ID,new ComponentName(c,CaptureWatchdogJob.class)).setPeriodic(15*60*1000L).setPersisted(true).build());
    }
    private static PendingIntent alarm(Context c,long generation,int flags){return PendingIntent.getBroadcast(c,8268,new Intent(c,CaptureResumeReceiver.class).setAction(RECOVER).putExtra("capture_epoch",generation),flags|PendingIntent.FLAG_IMMUTABLE);}
    static synchronized void cancel(Context c){
        PendingIntent alarm=alarm(c,0,PendingIntent.FLAG_NO_CREATE);if(alarm!=null){((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).cancel(alarm);alarm.cancel();}
        ((JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(JOB_ID);
    }
    static long retryDelay(int attempts){return attempts<=1?3000:attempts==2?10000:attempts==3?30000:60000;}
}
