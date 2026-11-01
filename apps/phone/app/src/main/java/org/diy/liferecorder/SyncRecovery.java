package org.diy.liferecorder;
import android.content.*;import android.bluetooth.*;import android.os.*;
/** Restores only a receiver the user previously enabled. No microphone or cloud request is started. */
public final class SyncRecovery extends BroadcastReceiver {
 static void restore(Context c){if(!c.getSharedPreferences("background-sync",0).getBoolean("enabled",false)||SyncService.running)return;try{if(Build.VERSION.SDK_INT>=31&&c.checkSelfPermission("android.permission.BLUETOOTH_CONNECT")!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;BluetoothManager m=(BluetoothManager)c.getSystemService(Context.BLUETOOTH_SERVICE);if(m!=null&&m.getAdapter()!=null&&m.getAdapter().isEnabled())c.startForegroundService(new Intent(c,SyncService.class));}catch(Exception ignored){}}
 public void onReceive(Context c,Intent i){restore(c);}
}
