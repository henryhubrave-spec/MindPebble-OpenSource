package org.diy.watchrecorder;
import android.content.*;
/** Internal recovery alarms; the receiver never changes durable user intent. */
public final class CaptureResumeReceiver extends BroadcastReceiver {
    public void onReceive(Context c,Intent i){if(i!=null&&CaptureIntent.RECOVER.equals(i.getAction()))CaptureIntent.kick(c,i.getLongExtra("capture_epoch",-1),"recovery_alarm");}
}
