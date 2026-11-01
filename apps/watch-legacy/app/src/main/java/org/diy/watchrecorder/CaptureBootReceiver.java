package org.diy.watchrecorder;
import android.content.*;
/** Protected system broadcasts only; resumes an existing enabled recording session. */
public final class CaptureBootReceiver extends BroadcastReceiver {
    public void onReceive(Context c,Intent i){String action=i==null?"":i.getAction();if(Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)){
        CaptureDiagnostics.event(c,"system_resume",action);if(CaptureIntent.mode(c).equals(CaptureIntent.RUNNING))CaptureIntent.kick(c,CaptureIntent.epoch(c),action);
    }}
}
