package org.diy.watchrecorder;
import android.app.job.*;
/** Coarse backup for a killed process; alarms and sticky restart handle the faster path. */
public final class CaptureWatchdogJob extends JobService {
    public boolean onStartJob(JobParameters p){if(CaptureIntent.mode(this).equals(CaptureIntent.RUNNING))CaptureIntent.kick(this,CaptureIntent.epoch(this),"watchdog_job");else CaptureIntent.cancel(this);return false;}
    public boolean onStopJob(JobParameters p){return false;}
}
