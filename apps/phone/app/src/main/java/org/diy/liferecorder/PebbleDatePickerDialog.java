package org.diy.liferecorder;
import android.app.DatePickerDialog;import android.content.Context;
final class PebbleDatePickerDialog extends DatePickerDialog {
 PebbleDatePickerDialog(Context context,OnDateSetListener listener,int year,int month,int day){super(context,listener,year,month,day);}
 protected void onStart(){super.onStart();PebbleSurfaces.dialog(this,false);}
}
