package org.diy.liferecorder;

import android.content.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** A focused recording reader: original text, personal highlights and evidence-backed summaries. */
public final class RecordActivity extends AppScreen {
    private String id, detailTab = "transcript", playingPath = "";
    private JSONObject record;
    private final Map<String, LinearLayout> panels = new LinkedHashMap<>();
    private final Map<String, Button> tabs = new LinkedHashMap<>();
    private TranscriptViews.Controller transcript;
    private MediaPlayer player;
    private boolean ready, speechPlayback, seeking;
    private int sequence;
    private long savedPlayback;
    private float speed = 1;
    private TextView position;
    private Button playButton, speedButton;
    private SeekBar seek;
    private WaveformView wave;
    private final Handler handler = new Handler();

    public void onCreate(Bundle state) {
        super.onCreate(state); id = getIntent().getStringExtra("id");
        if (state != null) { detailTab = state.getString("detail_tab", "transcript"); savedPlayback = state.getLong("playback_ms"); speed = state.getFloat("speed", 1); playingPath = state.getString("playing_path", ""); speechPlayback = state.getBoolean("speech_playback"); }
        render();
        if (state == null && getIntent().hasExtra("seek_ms") && record != null) playSource(record, Math.max(0, getIntent().getLongExtra("seek_ms", 0)));
    }

    private void render() {
        int previousScroll = scroll == null ? 0 : scroll.getScrollY(); release(); panels.clear(); tabs.clear();
        try {
            record = store.audio(id); frame("录音详情");
            LinearLayout top = row(); body.addView(top);
            Button back = button(top, "‹ 返回", this::finish); back.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1));
            Button more = button(top, "更多", this::more); more.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1));
            gap(body, 12); body.addView(rawTitle(record.optString("title"), 23));
            String when = new java.text.SimpleDateFormat(AppLanguage.pattern(this, "M月d日 HH:mm"), AppLanguage.locale(this)).format(new Date(record.optLong("created")));
            body.addView(text(when + " · " + PlaybackTimeline.clock(record.optLong("duration")) + " · " + DetailEvidence.markers(record).length() + " 个重点", 13, MUTED));
            if (record.optString("path").isEmpty()) body.addView(text("原音已不在本机，已保存的文字与来源仍可查看。", 13, MUTED));
            gap(body, 20);
            LinearLayout tabrow = row(); body.addView(tabrow);
            String[] keys = {"transcript", "highlights", "summary"}, names = {"转写", "重点", "总结"};
            for (int i = 0; i < keys.length; i++) {
                final String key = keys[i]; Button tab = button(tabrow, names[i], () -> selectTab(key));
                tab.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1)); tabs.put(key, tab);
            }
            gap(body, 14);
            LinearLayout original = panel("transcript");
            DetailEvidence.processing(this, original, Collections.singletonList(record), true, this::render);
            transcript = TranscriptViews.show(this, original, Collections.singletonList(record), this::playSource);
            button(original, "转写与全文校对", this::transcriptionTools);
            LinearLayout highlights = panel("highlights"); DetailEvidence.highlights(this, highlights, Collections.singletonList(record), this::playSource);
            LinearLayout summary = panel("summary");
            DetailEvidence.processing(this, summary, Collections.singletonList(record), false, this::render);
            DetailEvidence.summary(this, summary, Collections.singletonList(record), this::playSource, () -> requestAnalysis(false));
            showTasks(summary); showContext(summary);
            button(summary, "补充记忆或待办", () -> new LocalDialog(this).setTitle("补充这段记录").setItems(new String[]{"添加候选记忆", "添加待办草稿"}, (d, choice) -> editText(choice == 0 ? "候选记忆" : "待办草稿", "", value -> {
                try { store.addCandidate(choice == 0 ? "facts" : "tasks", id, value); render(); notice.setText(choice == 0 ? "已加入回忆待审核" : "已加入待办草稿"); } catch (Exception e) { notice.setText("保存失败，请重试"); }
            })).show());
            selectTab(detailTab); fixedPlayer(); scroll.post(() -> scroll.scrollTo(0, previousScroll));
        } catch (Exception e) { frame("记录不可用"); notice.setText("记录已删除或文件不完整"); button(body, "返回", this::finish); }
    }

    private LinearLayout panel(String key) { LinearLayout panel = card(body); panels.put(key, panel); return panel; }
    private void selectTab(String selected) {
        detailTab = panels.containsKey(selected) ? selected : "transcript";
        for (String key : panels.keySet()) { panels.get(key).setVisibility(key.equals(detailTab) ? View.VISIBLE : View.GONE); Button tab = tabs.get(key); tab.setTextColor(key.equals(detailTab) ? ACCENT : MUTED); tab.setBackground(bg(key.equals(detailTab) ? TINT : PAPER, 24)); tab.setSelected(key.equals(detailTab)); }
    }

    private void fixedPlayer() {
        LinearLayout audio = column(); audio.setPadding(dp(20), dp(6), dp(20), dp(10)); audio.setBackgroundColor(PAPER); root.addView(audio);
        View line = new View(this); line.setBackgroundColor(LINE); audio.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        wave = new WaveformView(this, fraction -> playAt((long) (fraction * playbackDuration()), true));
        audio.addView(wave, new LinearLayout.LayoutParams(-1, dp(30)));
        if (playingPath.isEmpty() || !new File(playingPath).isFile()) { playingPath = record.optString("path"); speechPlayback = record.optInt("source_retained", 1) == 0; }
        if (!playingPath.isEmpty()) wave.post(() -> wave.load(new File(playingPath)));
        position = text("", 12, MUTED); audio.addView(position);
        seek = new SeekBar(this); seek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT)); seek.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT)); seek.setMax((int) Math.min(Integer.MAX_VALUE, Math.max(1, playbackDuration()))); seek.setProgress((int) savedPlayback); audio.addView(seek, new LinearLayout.LayoutParams(-1, dp(28)));
        seek.setContentDescription("音频播放进度"); seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) { savedPlayback = value; updatePosition(value); } }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) { playAt(bar.getProgress(), true); }
        });
        LinearLayout controls = row(); audio.addView(controls);
        Button rewind = button(controls, "↶ 15", () -> playAt(Math.max(0, current() - 15000), true)); rewind.setContentDescription("后退 15 秒");
        playButton = primary(controls, "播放", this::togglePlayback); playButton.setContentDescription("播放或暂停录音");
        Button forward = button(controls, "15 ↷", () -> playAt(Math.min(Math.max(0, playbackDuration() - 1), current() + 15000), true)); forward.setContentDescription("前进 15 秒");
        speedButton = button(controls, speedLabel(), this::chooseSpeed);
        for (int i = 0; i < controls.getChildCount(); i++) { Button control=(Button)controls.getChildAt(i); control.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1)); control.setCompoundDrawables(null,null,null,null); control.setPadding(dp(6),dp(6),dp(6),dp(6)); control.setSingleLine(true); control.setMinWidth(0); control.setMinimumWidth(0); }
        boolean playable = !playingPath.isEmpty() && new File(playingPath).isFile(); for (int i = 0; i < controls.getChildCount(); i++) controls.getChildAt(i).setEnabled(playable); seek.setEnabled(playable);
        updatePosition(savedPlayback);
    }

    private void more() {
        new LocalDialog(this).setTitle("这段录音").setItems(new String[]{"重命名", "导出音频、文字与总结", "跳过静音与存储管理", "刷新结果"}, (dialog, choice) -> {
            if (choice == 0) editText("记录标题", record.optString("title"), value -> { if (value.trim().isEmpty()) { notice.setText("标题不能为空"); return; } store.update("audio", id, "title", value.trim()); render(); });
            else if (choice == 1) startActivity(new Intent(this, ExportActivity.class).putExtra("source", id));
            else if (choice == 2) audioTools();
            else render();
        }).show();
    }

    private void transcriptionTools() {
        new LocalDialog(this).setTitle("转写与校对").setItems(new String[]{"使用离线语音包转写", "使用云端 API 转写", "填写或纠正全文", "重新匹配声纹（保留文字）"}, (dialog, choice) -> {
            try {
                if (choice == 0) { OfflinePacks packs = new OfflinePacks(this); if (packs.selected().isEmpty() || !packs.ready(packs.selected())) { startActivity(new Intent(this, OfflineActivity.class)); return; } confirmJob(id, "local_asr", new JSONObject().put("pack", packs.selected()).put("automatic", false), this::render); }
                else if (choice == 1) confirmJob(id, "asr", new JSONObject().put("provider", new ProviderClient(this).snapshot("asr")).put("transcript_before", record.optString("transcript")), this::render);
                else if (choice == 2) editText("原文（全文修订将清除旧的逐句时间对齐）", record.optString("transcript"), value -> { store.update("audio", id, "transcript", value); render(); });
                else { JSONObject speech = AudioReview.object(record.optString("speech_json")); JSONArray segments = speech.optJSONArray("segments"); if (segments == null || segments.length() == 0) { notice.setText("请先完成逐段转写"); return; } confirmJob(id, "local_asr", new JSONObject().put("pack", speech.optString("engine", "lite")).put("automatic", false).put("speaker_only", true), this::render); }
            } catch (Exception e) { notice.setText(e.getMessage()); }
        }).show();
    }

    private void audioTools() {
        LinearLayout options = column(); options.setPadding(dp(16), dp(6), dp(16), dp(12));
        if (!record.optString("vad_path").isEmpty()) button(options, "播放语音副本 · 跳过静音", () -> { release(); playingPath = record.optString("vad_path"); speechPlayback = true; savedPlayback = 0; wave.load(new File(playingPath)); playAt(0, true); });
        if (record.optInt("source_retained", 1) == 1 && !record.optString("path").isEmpty()) {
            if (!record.optString("vad_path").isEmpty()) button(options, "播放完整原音", () -> { release(); playingPath = record.optString("path"); speechPlayback = false; savedPlayback = 0; wave.load(new File(playingPath)); playAt(0, true); });
            button(options, "在本机生成语音副本", () -> confirmJob(id, "vad", new JSONObject(), this::render));
            if (!record.optString("vad_map").isEmpty()) button(options, "仅保留语音，释放原音频空间", () -> new LocalDialog(this).setTitle("已试听确认没有遗漏？").setMessage("VAD 可能漏掉轻声、远场等声音。此操作会删除手机原音频，只保留精简版和时间映射；若未检测到语音，则仅保留时间与上下文。不会自动删除手表原件。")
                .setPositiveButton("确认，仅留语音", (d, which) -> { release(); background(() -> store.retainSpeechOnly(id), () -> { playingPath = ""; savedPlayback = 0; render(); }); }).setNegativeButton("取消", null).show());
        }
        if (record.optInt("source_retained", 1) == 0) options.addView(text("原音频已由你释放；回听时会显示保留语音对应的原始时间。", 13, MUTED));
        AudioReview.show(this, options, record, () -> playSource(record, 0), this::afterStorageChanged);
        button(options, "删除与存储位置", () -> { release(); AudioActions.show(this, id, this::afterStorageChanged); });
        ScrollView container = new ScrollView(this); container.addView(options); new LocalDialog(this).setTitle("音频管理").setView(container).setPositiveButton("关闭", null).show();
    }

    private void afterStorageChanged() { try { store.audio(id); playingPath = ""; savedPlayback = 0; render(); } catch (Exception deleted) { finish(); } }

    private void showTasks(LinearLayout parent) throws Exception {
        JSONArray tasks = store.rows("SELECT * FROM tasks WHERE source=? ORDER BY created DESC", id); if (tasks.length() == 0) return;
        gap(parent, 16); parent.addView(title("待办建议", 17));
        JSONArray facts = AudioReview.object(record.optString("analysis")).optJSONArray("facts"), units = TranscriptDocument.units(record);
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.getJSONObject(i); LinearLayout item = inset(parent, TINT); item.addView(rawText(task.optString("text"), 15, INK));
            if (facts != null) for (int f = 0; f < facts.length(); f++) { JSONObject fact = facts.getJSONObject(f); if (!fact.optString("text").equals(task.optString("text"))) continue; for (int u = 0; u < units.length(); u++) { JSONObject unit = units.getJSONObject(u); if (unit.optString("unit_id").equals(fact.optString("unit_id"))) { item.addView(rawText("“" + fact.optString("quote") + "”", 13, MUTED)); if (unit.optBoolean("timed")) button(item, "▷ 原句 " + PlaybackTimeline.clock(unit.optLong("start_ms")), () -> playSource(record, unit.optLong("start_ms"))); } } }
            if (task.optString("state").equals("review")) button(item, "采用这项待办", () -> { store.update("tasks", task.optString("id"), "state", "accepted"); render(); });
            else item.addView(text(task.optString("state").equals("done") ? "已完成" : "已加入生活待办", 12, SAGE));
        }
    }

    private void showContext(LinearLayout parent) throws Exception {
        JSONArray observations = store.rows("SELECT payload FROM context WHERE source=? ORDER BY created", id); if (observations.length() == 0) return;
        LinearLayout context = column(); button(parent, "环境观察  ⌄", () -> context.setVisibility(context.getVisibility() == View.GONE ? View.VISIBLE : View.GONE)); parent.addView(context); context.setVisibility(View.GONE);
        context.addView(text(new PebbleContext(store, id).insight(), 13, MUTED)); context.addView(new SensorTraceView(this, store, id, "heart_rate", SAGE), new LinearLayout.LayoutParams(-1, dp(60)));
        context.addView(text("身体读数是离散观察，不直接等同于情绪。", 12, MUTED)); button(context, "结合这段环境观察复盘", () -> requestAnalysis(true));
        JSONObject result = AudioReview.object(record.optString("analysis")); try { LifeMemory.checkReading(store, result); if (!result.optString("context_review").isEmpty()) context.addView(rawText("AI 情境复盘\n" + result.optString("context_review"), 14, INK)); if (!result.optString("recovery_hint").isEmpty()) context.addView(rawText("AI 恢复建议\n" + result.optString("recovery_hint"), 13, MUTED)); } catch (Exception ignored) {}
    }

    private void requestAnalysis(boolean withContext) {
        try {
            if (TranscriptDocument.units(record).length() == 0) { notice.setText("请先转写或填写原文"); return; }
            JSONObject input = new JSONObject().put("provider", new ProviderClient(this).snapshot("llm")).put("transcript", record.optString("transcript"));
            if (withContext) {
                JSONArray observations = new JSONArray(), rows = store.rows("SELECT payload FROM context WHERE source=? ORDER BY created", id);
                for (int n = 0; n < rows.length(); n++) for (String line : rows.getJSONObject(n).optString("payload").split("\n")) { JSONObject observation = AudioReview.object(line); if (observation.optString("type").matches("heart_rate|light|pressure")) observations.put(observation); }
                if (observations.length() == 0) { notice.setText("这段录音没有环境观察，可先使用原文分析"); return; }
                if (observations.toString().length() > 30000) { notice.setText("环境观察较多，请选择较短录音复盘"); return; } input.put("context_snapshot", observations);
            }
            confirmJob(id, "analysis", input, this::render);
        } catch (Exception e) { notice.setText("请先配置分析 API"); }
    }

    private void playSource(JSONObject source, long sourceMs) {
        if (!id.equals(source.optString("id"))) { startActivity(new Intent(this, RecordActivity.class).putExtra("id", source.optString("id")).putExtra("seek_ms", sourceMs)); return; }
        boolean retained = source.optInt("source_retained", 1) == 0; long target = PlaybackTimeline.toPlayback(source, sourceMs, retained);
        if (target < 0) { notice.setText("这个时刻的声音未保留，原句与原始时间仍可查看"); return; }
        if (!source.optString("path").equals(playingPath) || speechPlayback != retained) { release(); playingPath = source.optString("path"); speechPlayback = retained; if (!playingPath.isEmpty()) wave.load(new File(playingPath)); }
        playAt(target, true);
    }

    private long playbackDuration() {
        if (ready && player != null) try { return player.getDuration(); } catch (Exception ignored) {}
        if (record == null) return 0;
        if (!speechPlayback) return record.optLong("duration"); JSONArray ranges = PlaybackTimeline.ranges(record); return ranges.length() == 0 ? 0 : ranges.optJSONObject(ranges.length() - 1).optLong("output_end_sample") / 16;
    }
    private long current() { if (ready && !seeking && player != null) try { return player.getCurrentPosition(); } catch (Exception ignored) {} return savedPlayback; }
    private void togglePlayback() {
        if (player != null && (!ready || seeking)) return;
        if (player != null) { if (player.isPlaying()) { player.pause(); playButton.setText("播放"); } else { player.start(); playButton.setText("暂停"); } }
        else playAt(savedPlayback, true);
    }
    private void playAt(long at, boolean start) {
        if (playingPath.isEmpty() || !new File(playingPath).isFile()) { notice.setText("没有可播放音频，文字仍可查看"); return; }
        final long target = Math.max(0, Math.min(Math.max(0, playbackDuration() - 1), at)); savedPlayback = target;
        if (player != null && ready) { seekPlayer(player, target, start); updatePosition(target); return; }
        release(); savedPlayback = target; final int token = ++sequence; MediaPlayer next = new MediaPlayer(); player = next; playButton.setText("准备中");
        try {
            next.setDataSource(playingPath); next.setOnPreparedListener(p -> {
                if (player != p || token != sequence) return; ready = true; seek.setMax(p.getDuration());
                seekPlayer(p, Math.min(target, Math.max(0, p.getDuration() - 1)), start);
                handler.removeCallbacks(progress); handler.post(progress);
            });
            next.setOnCompletionListener(p -> { if (p != player) return; savedPlayback = 0; p.setOnSeekCompleteListener(null); p.pause(); p.seekTo(0L, MediaPlayer.SEEK_CLOSEST); playButton.setText("播放"); updatePosition(0); });
            next.setOnErrorListener((p, what, extra) -> { if (p == player) { release(); playButton.setText("播放"); notice.setText("音频暂时无法播放，请检查本机文件"); } return true; }); next.prepareAsync();
        } catch (Exception e) { release(); playButton.setText("播放"); notice.setText("音频文件不可用"); }
    }
    private void seekPlayer(MediaPlayer target, long at, boolean start) {
        seeking = true; if (target.isPlaying()) target.pause(); final int token = sequence;
        target.setOnSeekCompleteListener(p -> {
            if (p != player || token != sequence) return;
            p.setOnSeekCompleteListener(null); seeking = false;
            p.setPlaybackParams(new PlaybackParams().setSpeed(speed)); if (start) p.start(); else p.pause();
            playButton.setText(start ? "暂停" : "播放"); updatePosition(p.getCurrentPosition());
        });
        target.seekTo(at, MediaPlayer.SEEK_CLOSEST);
    }
    private void updatePosition(long at) {
        if (position == null) return; String value = PlaybackTimeline.clock(at) + " / " + PlaybackTimeline.clock(playbackDuration());
        long original = PlaybackTimeline.toSource(record, at, speechPlayback); if (speechPlayback) value += " · 原音 " + PlaybackTimeline.clock(original);
        setLabel(position, value); if (wave != null) wave.progress(at / (float) Math.max(1, playbackDuration())); if (transcript != null && player != null) transcript.update(id, original);
    }
    private final Runnable progress = new Runnable() { public void run() { if (player != null && ready) { try { if (seeking) { handler.postDelayed(this, 250); return; } long at = current(); savedPlayback = at; seek.setProgress((int) at); updatePosition(at); playButton.setText(player.isPlaying() ? "暂停" : "播放"); } catch (Exception ignored) {} handler.postDelayed(this, 250); } } };
    private String speedLabel() { return (speed == 1 ? "1" : String.valueOf(speed)) + "×"; }
    private void chooseSpeed() { new LocalDialog(this).setTitle("播放速度").setItems(new String[]{"0.75×", "1×", "1.25×", "1.5×", "2×"}, (dialog, choice) -> { speed = new float[]{.75f, 1, 1.25f, 1.5f, 2}[choice]; if (player != null && ready) { boolean playing = player.isPlaying(); player.setPlaybackParams(new PlaybackParams().setSpeed(speed)); if (!playing) player.pause(); } speedButton.setText(speedLabel()); }).show(); }
    private void release() { savedPlayback = current(); sequence++; ready = false; seeking = false; handler.removeCallbacks(progress); if (player != null) { player.release(); player = null; } }
    void onUnderstandingUpdated() { if (notice != null) notice.setText("处理结果已更新，可在「更多」中刷新查看"); }
    protected void onResume() { super.onResume(); if (record != null) try { JSONObject current = store.audio(id); if (current.optLong("transcript_revision") != record.optLong("transcript_revision") || !current.optString("analysis").equals(record.optString("analysis")) || !current.optString("path").equals(record.optString("path"))) render(); } catch (Exception ignored) {} }
    protected void onSaveInstanceState(Bundle out) { out.putString("detail_tab", detailTab); out.putLong("playback_ms", current()); out.putFloat("speed", speed); out.putString("playing_path", playingPath); out.putBoolean("speech_playback", speechPlayback); super.onSaveInstanceState(out); }
    public void onStop() { release(); super.onStop(); }
    public void onDestroy() { release(); super.onDestroy(); }
}
