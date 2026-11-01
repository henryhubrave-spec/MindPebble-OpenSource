package org.diy.liferecorder;

import android.content.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** One continuous reader, while source timestamps retain recording gaps and VAD omissions. */
public final class SessionActivity extends AppScreen {
    private final ArrayList<JSONObject> records = new ArrayList<>(), sourceRecords = new ArrayList<>();
    private final Map<String, LinearLayout> panels = new LinkedHashMap<>();
    private final Map<String, Button> tabs = new LinkedHashMap<>();
    private long[] offsets = new long[0];
    private long total, savedPosition;
    private int index, sequence;
    private String detailTab = "transcript";
    private MediaPlayer player;
    private boolean ready, seeking;
    private float speed = 1;
    private SeekBar seek;
    private WaveformView wave;
    private TextView position;
    private Button play, speedButton;
    private TranscriptViews.Controller transcript;
    private final Handler updates = new Handler();

    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) { savedPosition = state.getLong("position"); detailTab = state.getString("detail_tab", "transcript"); speed = state.getFloat("speed", 1); }
        load();
    }

    private void load() {
        release(); records.clear(); sourceRecords.clear(); total = 0;
        try {
            ArrayList<String> ids = getIntent().getStringArrayListExtra("ids"); if (ids == null || ids.isEmpty() || ids.size() > 1000) throw new IOException("没有选择录音");
            Set<String> seen = new HashSet<>();
            for (String id : ids) if (seen.add(id)) try { JSONObject audio = store.audio(id); sourceRecords.add(audio); if (!audio.optString("path").isEmpty() && new File(audio.optString("path")).isFile() && PlaybackTimeline.duration(audio) > 0) { audio.put("playback_ms", PlaybackTimeline.duration(audio)); records.add(audio); } } catch (Exception ignored) {}
            sourceRecords.sort((a, b) -> Long.compare(a.optLong("created"), b.optLong("created"))); records.sort((a, b) -> Long.compare(a.optLong("created"), b.optLong("created")));
            offsets = new long[records.size()]; for (int i = 0; i < records.size(); i++) { offsets[i] = total; total += records.get(i).optLong("playback_ms"); }
            savedPosition = Math.max(0, Math.min(Math.max(0, total - 1), savedPosition)); render();
        } catch (Exception e) { frame("连续回看"); notice.setText("记录已删除或不可用"); button(body, "返回", this::finish); }
    }

    private void render() throws Exception {
        frame("连续回看"); panels.clear(); tabs.clear(); LinearLayout top = row(); body.addView(top);
        Button back = button(top, "‹ 返回", this::finish); back.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1));
        Button more = button(top, "更多", this::more); more.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1));
        gap(body, 12); body.addView(title("连续回看", 24));
        if (sourceRecords.isEmpty()) { body.addView(text("这组记录已不可用", 14, MUTED)); return; }
        long start = sourceRecords.get(0).optLong("created"), end = start; int markers = 0;
        for (JSONObject source : sourceRecords) { end = Math.max(end, source.optLong("created") + source.optLong("duration")); markers += DetailEvidence.markers(source).length(); }
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("MM-dd HH:mm", AppLanguage.locale(this));
        body.addView(text(format.format(new Date(start)) + " — " + format.format(new Date(end)), 13, MUTED));
        body.addView(text(sourceRecords.size() + " 段录音 · 可回听 " + PlaybackTimeline.clock(total) + " · " + markers + " 个重点", 13, MUTED));
        if (records.size() < sourceRecords.size()) body.addView(text("部分原音不在本机，已保存的文字仍可查看。", 12, MUTED));
        gap(body, 20); LinearLayout tabrow = row(); body.addView(tabrow);
        String[] keys = {"transcript", "highlights", "summary"}, names = {"转写", "重点", "总结"};
        for (int i = 0; i < keys.length; i++) { final String key = keys[i]; Button tab = button(tabrow, names[i], () -> selectTab(key)); tab.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1)); tabs.put(key, tab); }
        gap(body, 14); LinearLayout original = panel("transcript"); DetailEvidence.processing(this, original, sourceRecords, true, this::load); transcript = TranscriptViews.show(this, original, sourceRecords, this::playSource);
        button(original, "转写或校对某一段录音", this::showSources);
        DetailEvidence.highlights(this, panel("highlights"), sourceRecords, this::playSource);
        LinearLayout summary = panel("summary"); DetailEvidence.processing(this, summary, sourceRecords, false, this::load); DetailEvidence.summary(this, summary, sourceRecords, this::playSource, this::analyze);
        selectTab(detailTab); fixedPlayer();
    }

    private LinearLayout panel(String key) { LinearLayout panel = card(body); panels.put(key, panel); return panel; }
    private void selectTab(String selected) {
        detailTab = panels.containsKey(selected) ? selected : "transcript";
        for (String key : panels.keySet()) { boolean active = key.equals(detailTab); panels.get(key).setVisibility(active ? View.VISIBLE : View.GONE); tabs.get(key).setSelected(active); tabs.get(key).setTextColor(active ? ACCENT : MUTED); tabs.get(key).setBackground(bg(active ? TINT : PAPER, 24)); }
    }
    private void more() { new LocalDialog(this).setTitle("这组录音").setItems(new String[]{"查看原始录音与存储位置", "刷新处理结果"}, (dialog, choice) -> { if (choice == 0) showSources(); else load(); }).show(); }
    private void showSources() {
        String[] labels = new String[sourceRecords.size()]; for (int i = 0; i < labels.length; i++) { JSONObject source = sourceRecords.get(i); labels[i] = TranscriptDocument.clock(source.optLong("created")) + " · " + source.optString("title"); }
        new LocalDialog(this).setTitle("原始录音").setItems(labels, (dialog, choice) -> openRecord(sourceRecords.get(choice).optString("id"))).show();
    }
    private void analyze() {
        try { JSONArray ids = new JSONArray(); for (JSONObject source : sourceRecords) if (TranscriptDocument.units(source).length() > 0) ids.put(source.getString("id")); if (ids.length() == 0) { notice.setText("请先完成至少一段转写"); return; } confirmJob("", "analysis_batch", new JSONObject().put("sources", ids).put("provider", new ProviderClient(this).snapshot("llm")), this::load); }
        catch (Exception e) { notice.setText("请先配置分析 API"); }
    }

    private void fixedPlayer() {
        LinearLayout audio = column(); audio.setPadding(dp(20), dp(6), dp(20), dp(10)); audio.setBackgroundColor(PAPER); root.addView(audio);
        View line = new View(this); line.setBackgroundColor(LINE); audio.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        wave = new WaveformView(this, fraction -> jump((long) (fraction * total), true)); audio.addView(wave, new LinearLayout.LayoutParams(-1, dp(30)));
        ArrayList<File> files = new ArrayList<>(); ArrayList<Long> durations = new ArrayList<>(); for (JSONObject source : records) { files.add(new File(source.optString("path"))); durations.add(source.optLong("playback_ms")); } if (!files.isEmpty()) wave.post(() -> wave.load(files, durations));
        position = text("", 12, MUTED); audio.addView(position); seek = new SeekBar(this); seek.setMax(10000); seek.setProgress((int) (savedPosition * 10000 / Math.max(1, total))); seek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT)); seek.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT)); seek.setContentDescription("连续录音播放进度"); audio.addView(seek, new LinearLayout.LayoutParams(-1, dp(28)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar bar, int progress, boolean user) { if (user) updatePosition(progress * total / 10000); } public void onStartTrackingTouch(SeekBar bar) {} public void onStopTrackingTouch(SeekBar bar) { jump(bar.getProgress() * total / 10000, true); } });
        LinearLayout controls = row(); audio.addView(controls); Button rewind = button(controls, "↶ 15", () -> jump(Math.max(0, current() - 15000), true)); rewind.setContentDescription("后退 15 秒");
        play = primary(controls, "播放", () -> { if (player == null) jump(savedPosition, true); else if (ready && !seeking) { if (player.isPlaying()) { player.pause(); play.setText("播放"); } else { player.start(); play.setText("暂停"); } } });
        Button forward = button(controls, "15 ↷", () -> jump(Math.min(total - 1, current() + 15000), true)); forward.setContentDescription("前进 15 秒");
        speedButton = button(controls, speedLabel(), () -> new LocalDialog(this).setTitle("播放速度").setItems(new String[]{"0.75×", "1×", "1.25×", "1.5×", "2×"}, (d, choice) -> { speed = new float[]{.75f, 1, 1.25f, 1.5f, 2}[choice]; if (player != null && ready) { boolean playing = player.isPlaying(); player.setPlaybackParams(new PlaybackParams().setSpeed(speed)); if (!playing) player.pause(); } speedButton.setText(speedLabel()); }).show());
        for (int i = 0; i < controls.getChildCount(); i++) { controls.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1)); controls.getChildAt(i).setEnabled(!records.isEmpty()); Button control=(Button)controls.getChildAt(i);control.setCompoundDrawables(null,null,null,null);control.setPadding(dp(6),dp(6),dp(6),dp(6));control.setSingleLine(true);control.setMinWidth(0);control.setMinimumWidth(0); } seek.setEnabled(!records.isEmpty()); updatePosition(savedPosition);
    }
    private String speedLabel() { return (speed == 1 ? "1" : String.valueOf(speed)) + "×"; }
    private void playSource(JSONObject source, long sourceMs) {
        for (int i = 0; i < records.size(); i++) if (source.optString("id").equals(records.get(i).optString("id"))) {
            long local = PlaybackTimeline.toPlayback(source, sourceMs, source.optInt("source_retained", 1) == 0); if (local < 0) { notice.setText("这个时刻的声音未保留，原句与原始时间仍可查看"); return; } jump(offsets[i] + Math.min(Math.max(0, records.get(i).optLong("playback_ms") - 1), local), true); return;
        }
        if (source.optString("path").isEmpty()) { notice.setText("这句来源的原音不在本机，文字仍可核对"); return; }
        startActivity(new Intent(this, RecordActivity.class).putExtra("id", source.optString("id")).putExtra("seek_ms", sourceMs));
    }
    long current() { if (player != null && ready && !seeking) try { return offsets[index] + player.getCurrentPosition(); } catch (Exception ignored) {} return savedPosition; }
    void jump(long at, boolean start) {
        if (records.isEmpty()) return; at = Math.max(0, Math.min(total - 1, at)); int target = findIndex(at); final long local = at - offsets[target];
        if (player != null && ready && target == index) { seekPlayer(player, local, start); savedPosition = at; updatePosition(at); return; }
        release(); savedPosition = at; index = target; int token = ++sequence; MediaPlayer next = new MediaPlayer(); player = next; play.setText("准备中");
        try {
            next.setDataSource(records.get(index).getString("path")); next.setOnPreparedListener(p -> { if (token != sequence || p != player) return; ready = true; seekPlayer(p, Math.min(local, Math.max(0, p.getDuration() - 1)), start); updates.removeCallbacks(tick); updates.post(tick); });
            next.setOnCompletionListener(p -> { if (p != player) return; if (index + 1 < records.size()) jump(offsets[index + 1], true); else { release(); savedPosition = 0; seek.setProgress(10000); wave.progress(1); position.setText(PlaybackTimeline.clock(total) + " / " + PlaybackTimeline.clock(total)); play.setText("播放"); } });
            next.setOnErrorListener((p, what, extra) -> { if (p == player) { release(); play.setText("播放"); notice.setText("片段无法播放，可继续核对文字或选择另一段"); } return true; }); next.prepareAsync();
        } catch (Exception e) { release(); play.setText("播放"); notice.setText("音频文件不可用"); }
    }
    private void seekPlayer(MediaPlayer target, long at, boolean start) {
        seeking = true; if (target.isPlaying()) target.pause(); final int token = sequence;
        target.setOnSeekCompleteListener(p -> { if (p != player || token != sequence) return; p.setOnSeekCompleteListener(null); seeking = false; p.setPlaybackParams(new PlaybackParams().setSpeed(speed)); if (start) p.start(); else p.pause(); play.setText(start ? "暂停" : "播放"); updatePosition(offsets[index] + p.getCurrentPosition()); });
        target.seekTo(at, MediaPlayer.SEEK_CLOSEST);
    }
    private int findIndex(long at) { for (int i = 0; i < records.size(); i++) if (at < offsets[i] + records.get(i).optLong("playback_ms")) return i; return Math.max(0, records.size() - 1); }
    private void updatePosition(long at) {
        if (position == null) return; String text = PlaybackTimeline.clock(at) + " / " + PlaybackTimeline.clock(total);
        if (!records.isEmpty()) { int part = findIndex(at); JSONObject source = records.get(part); long original = PlaybackTimeline.toSource(source, Math.max(0, at - offsets[part]), source.optInt("source_retained", 1) == 0); text += " · 原录音 " + TranscriptDocument.clock(source.optLong("created") + original); if (transcript != null && player != null) transcript.update(source.optString("id"), original); }
        setLabel(position, text); if (wave != null) wave.progress(at / (float) Math.max(1, total));
    }
    private final Runnable tick = new Runnable() { public void run() { if (player != null && ready) { if (seeking) { updates.postDelayed(this, 250); return; } long at = current(); savedPosition = at; seek.setProgress((int) (at * 10000 / Math.max(1, total))); updatePosition(at); play.setText(player.isPlaying() ? "暂停" : "播放"); updates.postDelayed(this, 250); } } };
    private void release() { savedPosition = current(); sequence++; ready = false; seeking = false; updates.removeCallbacks(tick); if (player != null) { player.release(); player = null; } }
    void onUnderstandingUpdated() { if (notice != null) notice.setText("处理结果已更新，可在「更多」中刷新查看"); }
    protected void onResume() {
        super.onResume();
        for (JSONObject shown : sourceRecords) try { JSONObject current = store.audio(shown.optString("id")); if (current.optLong("transcript_revision") != shown.optLong("transcript_revision") || !current.optString("analysis").equals(shown.optString("analysis")) || !current.optString("path").equals(shown.optString("path"))) { load(); break; } } catch (Exception missing) { load(); break; }
    }
    protected void onSaveInstanceState(Bundle state) { state.putLong("position", current()); state.putString("detail_tab", detailTab); state.putFloat("speed", speed); super.onSaveInstanceState(state); }
    protected void onPause() { release(); if (play != null) play.setText("播放"); super.onPause(); }
    public void onDestroy() { release(); super.onDestroy(); }
}
