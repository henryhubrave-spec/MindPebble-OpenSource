package org.diy.liferecorder;
import android.app.TimePickerDialog;import android.content.Context;
final class PebbleTimePickerDialog extends TimePickerDialog {
 PebbleTimePickerDialog(Context context,OnTimeSetListener listener,int hour,int minute,boolean fullDay){super(context,listener,hour,minute,fullDay);}
 protected void onStart(){super.onStart();PebbleSurfaces.dialog(this,false);}
}
