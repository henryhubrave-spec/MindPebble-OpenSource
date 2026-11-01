package org.diy.watchrecorder;
import android.app.*;import android.os.*;import android.content.*;import android.view.*;import android.widget.*;import android.graphics.drawable.GradientDrawable;
/** The user controls local wake detection; stopping recording also stops listening. */
public final class WakeWordActivity extends Activity {
 private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private TextView text(String value,int size){TextView v=new TextView(this);v.setText(WatchLanguage.text(this,value));v.setTextColor(0xffefc686);v.setTextSize(size);v.setGravity(Gravity.CENTER);return v;}
 public void onCreate(Bundle b){super.onCreate(b);FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xff000000);ScrollView scroll=new ScrollView(this);root.addView(scroll,new FrameLayout.LayoutParams((int)(Math.min(getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels)*.78f),-1,Gravity.CENTER));LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(0,dp(20),0,dp(36));box.setGravity(Gravity.CENTER);scroll.addView(box);
  box.addView(text("你好小石头",17));TextView summary=text("持续记录时生效\n暂停或停止也停止监听",11);summary.setPadding(0,dp(8),0,dp(6));box.addView(summary);
  CheckBox enabled=new CheckBox(this);enabled.setText(WatchLanguage.text(this,"开启语音唤醒"));enabled.setTextSize(12);enabled.setTextColor(0xffefc686);enabled.setChecked(WakeWordEngine.enabled(this));box.addView(enabled,new LinearLayout.LayoutParams(-1,dp(44)));
  Button apply=new Button(this);apply.setText(WatchLanguage.text(this,"保存"));apply.setTextSize(12);apply.setTextColor(0xffefc686);GradientDrawable pill=new GradientDrawable();pill.setColor(0xff3b2d1c);pill.setCornerRadius(dp(24));apply.setBackground(pill);box.addView(apply,new LinearLayout.LayoutParams(-1,dp(40)));
  TextView detail=text("开启后，熄屏说出唤醒词会震动、亮屏并打开 MindPebble。\n\n声音仅在手表本地检测；通话时让出麦克风。会增加耗电，嘈杂环境下可能漏唤醒或误唤醒。",11);detail.setPadding(0,dp(12),0,0);box.addView(detail);
  apply.setOnClickListener(v->{if(!getSharedPreferences("wake-word",0).edit().putBoolean("enabled",enabled.isChecked()).commit()){Toast.makeText(this,"设置未保存，请重试",1).show();return;}CaptureDiagnostics.event(this,"wake_setting","enabled="+enabled.isChecked());if(RecordingService.alive||CaptureIntent.mode(this).equals(CaptureIntent.RUNNING))startForegroundService(new Intent(this,RecordingService.class).setAction(RecordingService.WAKE_SETTINGS));finish();});setContentView(root);
 }
}
