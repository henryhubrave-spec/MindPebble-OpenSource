package org.diy.liferecorder;

import android.content.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** One readable transcript, with in-place playback and optional editing rather than duplicate text lists. */
final class TranscriptViews {
    interface SeekAction { void seek(JSONObject source, long sourceMs); }

    static Controller show(AppScreen screen, LinearLayout parent, List<JSONObject> records, SeekAction seek) throws Exception {
        Controller controller = new Controller(screen, parent, records, seek);
        controller.build();
        return controller;
    }

    static final class Controller {
        private final AppScreen screen;
        private final LinearLayout parent;
        private final List<JSONObject> records;
        private final SeekAction seek;
        private final ArrayList<JSONObject> units = new ArrayList<>();
        private final Map<String, JSONObject> sources = new LinkedHashMap<>();
        private final ArrayList<LinearLayout> views = new ArrayList<>();
        private LinearLayout lines;
        private Button more, followButton;
        private int shown, active = -1;
        private boolean full, follow = true;
        private String currentSource = "";
        private long currentMs = -1;

        Controller(AppScreen screen, LinearLayout parent, List<JSONObject> records, SeekAction seek) {
            this.screen = screen; this.parent = parent; this.records = records; this.seek = seek;
        }

        void build() throws Exception {
            LinearLayout actions = screen.row(); parent.addView(actions);
            screen.button(actions, "复制原文", this::copy);
            followButton = screen.button(actions, "跟随播放 · 开", () -> {
                follow = !follow; followButton.setText(follow ? "跟随播放 · 开" : "跟随播放 · 关");
                if (follow && active >= 0) scrollToActive();
            });
            parent.addView(screen.text("点一句回听原声；说话人菜单中可校对文字与声纹。", 12, screen.MUTED));
            lines = screen.column(); parent.addView(lines); draw();
            boolean hidden = false;
            for (JSONObject record : records) if (TranscriptDocument.units(record, true).length() > TranscriptDocument.units(record).length()) hidden = true;
            if (hidden) {
                Button all = screen.button(parent, "显示隐藏语句", () -> {});
                all.setOnClickListener(v -> { full = !full; all.setText(full ? "收起隐藏语句" : "显示隐藏语句"); try { draw(); } catch (Exception e) { screen.notice.setText("原文读取失败，请刷新"); } });
            }
        }

        private void draw() throws Exception {
            lines.removeAllViews(); units.clear(); sources.clear(); views.clear(); shown = 0; active = -1; more = null;
            for (JSONObject record : records) {
                sources.put(record.optString("id"), record);
                JSONArray parts = TranscriptDocument.units(record, full);
                for (int i = 0; i < parts.length(); i++) units.add(parts.getJSONObject(i));
            }
            if (units.isEmpty()) {
                lines.addView(screen.text("尚无可显示的原文。原音仍可播放，转写完成后将在这里逐段显示。", 14, screen.MUTED));
                return;
            }
            append(30); update(currentSource, currentMs);
        }

        private void append(int until) throws Exception {
            if (more != null) { lines.removeView(more); more = null; }
            int end = Math.min(until, units.size());
            for (int i = shown; i < end; i++) {
                JSONObject unit = units.get(i), source = sources.get(unit.getString("source_id"));
                LinearLayout line = screen.column(); line.setPadding(screen.dp(12), screen.dp(8), screen.dp(12), screen.dp(10));
                LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.bottomMargin = screen.dp(8); lines.addView(line, layout); views.add(line);
                LinearLayout meta = screen.row(); line.addView(meta);
                String time = unit.optBoolean("timed") ? PlaybackTimeline.clock(unit.optLong("start_ms")) + " – " + PlaybackTimeline.clock(unit.optLong("end_ms")) : "时间未对齐";
                if (records.size() > 1 && unit.optBoolean("timed")) time = TranscriptDocument.clock(source.optLong("created") + unit.optLong("start_ms"));
                TextView at = screen.text(time, 12, screen.SAGE); at.setMinHeight(screen.dp(48)); at.setGravity(Gravity.CENTER_VERTICAL); meta.addView(at, new LinearLayout.LayoutParams(0, -2, 1));
                TextView speaker = screen.rawText(unit.optString("speaker", "身份不确定") + "  ⌄", 12, screen.MUTED);
                speaker.setMinHeight(screen.dp(48)); speaker.setPadding(screen.dp(10), 0, 0, 0); speaker.setGravity(Gravity.CENTER_VERTICAL); speaker.setContentDescription("校对文字与说话人：" + unit.optString("speaker"));
                meta.addView(speaker); speaker.setOnClickListener(v -> openVoice(source, unit));
                TextView words = screen.rawText(unit.optString("text"), 16, screen.INK); words.setLineSpacing(screen.dp(5), 1); words.setMinHeight(screen.dp(48)); line.addView(words);
                View.OnClickListener play = v -> { if (unit.optBoolean("timed")) seek.seek(source, unit.optLong("start_ms")); else screen.notice.setText("这段文字没有时间对齐，可用下方播放器回听原音"); };
                line.setOnClickListener(play); at.setOnClickListener(play); words.setOnClickListener(play);
                words.setContentDescription(unit.optString("text") + (unit.optBoolean("timed") ? "，点按从这一句播放" : "，无时间对齐"));
                words.setOnLongClickListener(v -> { ((ClipboardManager) screen.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("原句", unit.optString("text"))); screen.notice.setText("已复制这句原文"); return true; });
            }
            shown = end;
            if (shown < units.size()) more = screen.button(lines, "展开更多原文 · 还有 " + (units.size() - shown) + " 句", () -> { try { append(shown + 30); } catch (Exception e) { screen.notice.setText("读取原文失败"); } });
        }

        void update(String source, long sourceMs) {
            currentSource = source; currentMs = sourceMs;
            int next = -1;
            for (int i = 0; i < units.size(); i++) {
                JSONObject unit = units.get(i);
                if (unit.optBoolean("timed") && source.equals(unit.optString("source_id")) && sourceMs >= unit.optLong("start_ms") && sourceMs < unit.optLong("end_ms")) { next = i; break; }
            }
            if (next == active) return;
            if (active >= 0 && active < views.size()) views.get(active).setBackgroundColor(Color.TRANSPARENT);
            active = next;
            if (active < 0) return;
            try { if (active >= shown) append(((active / 30) + 1) * 30); } catch (Exception ignored) { return; }
            views.get(active).setBackground(screen.bg(screen.TINT, 14));
            if (follow) scrollToActive();
        }

        private void scrollToActive() {
            if (active < 0 || active >= views.size() || !parent.isShown()) return;
            final View line = views.get(active);
            line.post(() -> {
                if (!parent.isShown() || screen.scroll == null) return;
                int[] point = new int[2], viewport = new int[2]; line.getLocationOnScreen(point); screen.scroll.getLocationOnScreen(viewport);
                if (point[1] < viewport[1] || point[1] + line.getHeight() > viewport[1] + screen.scroll.getHeight())
                    screen.scroll.smoothScrollTo(0, Math.max(0, screen.scroll.getScrollY() + point[1] - viewport[1] - screen.dp(20)));
            });
        }

        private void copy() {
            StringBuilder out = new StringBuilder();
            for (JSONObject unit : units) {
                JSONObject source = sources.get(unit.optString("source_id"));
                if (out.length() > 0) out.append("\n\n");
                out.append(unit.optBoolean("timed") ? TranscriptDocument.clock(source.optLong("created") + unit.optLong("start_ms")) + " – " + TranscriptDocument.clock(source.optLong("created") + unit.optLong("end_ms")) : "时间未对齐")
                    .append(" · ").append(unit.optString("speaker")).append('\n').append(unit.optString("text"));
            }
            if (out.length() == 0) { screen.notice.setText("暂无原文可复制"); return; }
            ((ClipboardManager) screen.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("完整原文", out.toString()));
            screen.notice.setText(full ? "已复制全部原文、时间与说话人" : "已复制当前可见原文、时间与说话人");
        }

        private void openVoice(JSONObject source, JSONObject unit) {
            if (!unit.has("segment_index")) { screen.notice.setText("这段文字未逐段对齐，可在转写工具中修改全文"); return; }
            new LocalDialog(screen).setTitle("校对这句原文").setItems(new String[]{"纠正文字", "修改说话人名称", "声纹校准与多段合并"}, (dialog, choice) -> {
                if (choice < 2) {
                    screen.editText(choice == 0 ? "纠正文字" : "说话人名称", unit.optString(choice == 0 ? "text" : "speaker"), value -> {
                        try {
                            JSONObject current = screen.store.audio(source.getString("id"));
                            if (current.optLong("transcript_revision") != source.optLong("transcript_revision")) throw new java.io.IOException("原文已更新，请刷新后再修改");
                            if (choice == 1) SpeakerActions.label(screen.store, source, unit, "", value);
                            else {
                                JSONObject speech = new JSONObject(current.getString("speech_json")); JSONArray parts = speech.getJSONArray("segments");
                                parts.getJSONObject(unit.getInt("segment_index")).put("text", value.trim()).put("text_confirmed", true);
                                StringBuilder text = new StringBuilder(); for (int i = 0; i < parts.length(); i++) { if (i > 0) text.append('\n'); text.append(parts.getJSONObject(i).optString("text")); }
                                speech.put("text", text.toString()); screen.store.editSpeech(source.getString("id"), current.optLong("transcript_revision"), speech);
                            }
                            screen.recreate();
                        } catch (Exception e) { screen.notice.setText(e.getMessage()); }
                    });
                    return;
                }
                try {
                    JSONArray ids = new JSONArray(); for (String id : sources.keySet()) ids.put(id);
                    screen.startActivity(new Intent(screen, VoiceSegmentActivity.class).putExtra("id", source.getString("id")).putExtra("segment_index", unit.getInt("segment_index")).putExtra("selection", new JSONObject().put("sources", ids).toString()));
                } catch (Exception e) { screen.notice.setText("语句已变化，请刷新"); }
            }).show();
        }
    }
}
