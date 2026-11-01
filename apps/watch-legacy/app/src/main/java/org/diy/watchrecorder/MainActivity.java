package org.diy.watchrecorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.File;

/** API26 recording controls with explicit microphone state and local pairing entry. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private TextView title, microphone, saved, note;
    private Button start, pause, stop, details, link, mark, wake;
    private CheckBox consent;
    private Companion companion;
    private String detailMessage = "";
    private final Runnable refresh = new Runnable() {
        public void run() { update(); handler.postDelayed(this, 1000); }
    };
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(int size, int color) {
        TextView t = new TextView(this){public void setText(CharSequence value,TextView.BufferType type){super.setText(WatchLanguage.text(getContext(),value),type);}}; t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.CENTER); t.setIncludeFontPadding(false); return t;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        int width = Math.round(Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 0.78f);
        root.addView(scroll, new FrameLayout.LayoutParams(width, -1, Gravity.CENTER));
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER); box.setPadding(0, dp(12), 0, dp(12)); scroll.addView(box);
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER);
        companion = new Companion(); heading.addView(companion, new LinearLayout.LayoutParams(dp(24), dp(24)));
        title = text(17, Color.rgb(239, 198, 134)); title.setTypeface(null, Typeface.BOLD);
        heading.addView(title); box.addView(heading, new LinearLayout.LayoutParams(-1, dp(28)));
        microphone = text(11, Color.rgb(191, 200, 183)); box.addView(microphone, new LinearLayout.LayoutParams(-1, dp(20)));
        saved = text(11, Color.rgb(155, 166, 145)); box.addView(saved, new LinearLayout.LayoutParams(-1, dp(19)));
        consent = new CheckBox(this); consent.setText(WatchLanguage.text(this,"已告知相关人员\n并获同意")); consent.setTextSize(12);
        consent.setTextColor(Color.rgb(210, 216, 201)); consent.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(consent, new LinearLayout.LayoutParams(-1, dp(44)));
        start = button(box, "开启持续记录", RecordingService.START, false);
        pause = button(box, "暂停", RecordingService.PAUSE, false);
        stop = button(box, "停止并保存", RecordingService.STOP, true);
        mark = button(box,"标记重点",RecordingService.MARK,false);
        mark.setLayoutParams(new LinearLayout.LayoutParams(dp(140),dp(30)));
        mark.setOnClickListener(v->{startForegroundService(new Intent(this,RecordingService.class).setAction(RecordingService.MARK));Toast.makeText(this,"已标记当前时刻",Toast.LENGTH_SHORT).show();mark.setEnabled(false);handler.postDelayed(()->mark.setEnabled(true),1000);});
        link = button(box,"连接手机 / 同步",null,false);
        link.setOnClickListener(v->startActivity(new Intent(this,LinkActivity.class)));
        details = button(box, "查看中断详情", null, false);
        details.setOnClickListener(v -> new WatchDialog(this).setTitle("记录详情")
                .setMessage(detailMessage + "\n\n完整录音仍保留；临时文件需要单独检查。")
                .setPositiveButton("知道了", null).show());
        note = text(10, Color.rgb(155, 166, 145)); box.addView(note, new LinearLayout.LayoutParams(-1, dp(30)));
        wake=button(box,"语音唤醒",null,false);wake.setOnClickListener(v->startActivity(new Intent(this,WakeWordActivity.class)));
        setContentView(root); update(); voiceWake(getIntent());
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);voiceWake(i);}
    private void voiceWake(Intent i){if(i==null||!i.getBooleanExtra("voice_wake",false))return;i.removeExtra("voice_wake");getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON|android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);handler.postDelayed(()->getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON|android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON),10000);}
    private Button button(LinearLayout box, String label, String action, boolean isStop) {
        Button b = new Button(this){public void setText(CharSequence value,TextView.BufferType type){super.setText(WatchLanguage.text(getContext(),value),type);}}; b.setText(label); b.setTextSize(12); b.setAllCaps(false);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(6), 0, dp(6), 0);
        b.setTextColor(isStop ? Color.rgb(237, 155, 145) : Color.rgb(239, 198, 134));
        GradientDrawable background = new GradientDrawable(); background.setCornerRadius(dp(24));
        background.setColor(isStop ? Color.rgb(37, 23, 22) : Color.rgb(59, 45, 28));
        if (isStop) background.setStroke(dp(1), Color.rgb(89, 51, 48));
        b.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(isStop ? 140 : 158), dp(44));
        params.topMargin = dp(3); box.addView(b, params);
        b.setOnClickListener(v -> {
            if (RecordingService.START.equals(action)) {
                if (!consent.isChecked()) {
                    if (RecordingService.alive || !CaptureIntent.mode(this).equals(CaptureIntent.OFF)) {
                        new WatchDialog(this).setTitle("继续记录")
                            .setMessage("请确认已告知相关人员并获得录音同意。")
                            .setPositiveButton("已确认，继续", (dialog, which) -> {consent.setChecked(true); start.performClick();})
                            .setNegativeButton("取消", null).show();
                    } else Toast.makeText(this, "请先确认录音知情同意", Toast.LENGTH_LONG).show();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10); return;
                }
            }
            startForegroundService(new Intent(this, RecordingService.class).setAction(action));
        });
        return b;
    }
    private void update() {
        SharedPreferences preferences = getSharedPreferences("recorder", 0);
        String state = preferences.getString("state", "idle");
        boolean live = RecordingService.alive;
        boolean recording = live && state.equals("recording");
        boolean wanted=CaptureIntent.mode(this).equals(CaptureIntent.RUNNING);
        boolean recovering=wanted&&!recording;
        boolean paused = CaptureIntent.mode(this).equals(CaptureIntent.PAUSED);
        boolean interrupted = !live && (state.equals("recording") || state.equals("paused"));
        boolean error = interrupted || state.equals("error");
        File[] files = new File(getFilesDir(), "recordings").listFiles((dir, name) -> name.endsWith(".m4a"));
        title.setText(recording ? "生活记录中" : recovering ? "正在自动恢复" : paused ? "记录已暂停" : error ? "记录已中断" : "生活记录");
        companion.resting = !recording; companion.invalidate();
        microphone.setText(recording ? "● 麦克风已开启" : "麦克风已关闭");
        microphone.setTextColor(recording ? Color.rgb(227, 143, 126) : Color.rgb(191, 200, 183));
        saved.setText("已保存 " + (files == null ? 0 : files.length) + " 段 · 本地已保存");
        consent.setVisibility(live || wanted || paused ? View.GONE : View.VISIBLE);
        start.setText(recovering ? "立即重试" : paused ? "继续记录" : "开始记录"); start.setVisibility(recording ? View.GONE : View.VISIBLE);
        pause.setVisibility(recording || recovering ? View.VISIBLE : View.GONE);
        stop.setVisibility(live || wanted || paused ? View.VISIBLE : View.GONE);
        details.setVisibility(error || recovering ? View.VISIBLE : View.GONE);
        link.setVisibility(live?View.GONE:View.VISIBLE);
        mark.setVisibility(recording?View.VISIBLE:View.GONE);
        detailMessage = recovering ? preferences.getString("message","等待系统自动恢复")+"\n已开启自动恢复；手动暂停或停止会取消。" : interrupted ? "录音服务已中断，请检查临时文件" : preferences.getString("message", "待开始");
        String wakeStatus=getSharedPreferences("wake-word",0).getString("status","off");
        wake.setText(WakeWordEngine.enabled(this)?(!recording?"唤醒随记录开启":wakeStatus.equals("ready")?"唤醒词：你好小石头":wakeStatus.equals("loading")?"唤醒准备中":"唤醒暂不可用"):"语音唤醒");
        note.setText(recovering ? "麦克风未开启 · 等待恢复\n可暂停或停止自动重试" : recording ? (WakeWordEngine.enabled(this)&&wakeStatus.equals("ready")?"持续记录 · 可说你好小石头":"持续记录 · 意外中断自动恢复") : paused ? "手动暂停 · 不会自动恢复" : "AAC · 约 10 分钟分段\n停止后保存，卸载会删除");
    }
    private final class Companion extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean resting;
        Companion() { super(MainActivity.this); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.save(); canvas.scale(getWidth()/24f, getHeight()/24f);
            paint.setColor(Color.rgb(239,198,134)); paint.setStyle(Paint.Style.FILL);
            Path ears = new Path(); ears.moveTo(3,10); ears.lineTo(3,3); ears.lineTo(9,6);
            ears.lineTo(15,6); ears.lineTo(21,3); ears.lineTo(21,10); ears.close(); canvas.drawPath(ears,paint);
            canvas.drawOval(3,6,21,22,paint);
            paint.setColor(Color.rgb(54,42,28)); paint.setStrokeWidth(1.3f); paint.setStrokeCap(Paint.Cap.ROUND);
            if(resting) { canvas.drawLine(7,12,9,12,paint);canvas.drawLine(15,12,17,12,paint); }
            else { canvas.drawCircle(8,12,1,paint);canvas.drawCircle(16,12,1,paint); }
            paint.setStyle(Paint.Style.STROKE);canvas.drawArc(9,14,15,18,0,180,false,paint);
            canvas.restore();
        }
    }
    @Override public void onResume() { super.onResume(); handler.post(refresh); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
