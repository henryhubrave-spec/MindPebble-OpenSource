package org.diy.liferecorder;

import android.content.Intent;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Shared detail sections. Every derived statement keeps a route back to validated original text. */
final class DetailEvidence {
    static JSONArray markers(JSONObject audio) throws Exception {
        JSONArray raw = AudioReview.object(audio.optString("metadata")).optJSONArray("markers");
        TreeSet<Long> offsets = new TreeSet<>();
        if (raw != null) for (int i = 0; i < raw.length(); i++) {
            JSONObject marker = raw.optJSONObject(i); if (marker == null) continue;
            long at = marker.optLong("offset_ms", -1);
            if (at >= 0 && at < audio.optLong("duration")) offsets.add(at);
        }
        JSONArray result = new JSONArray(); for (long at : offsets) result.put(new JSONObject().put("offset_ms", at)); return result;
    }

    static JSONObject nearby(JSONObject audio, long at) throws Exception {
        JSONArray units = TranscriptDocument.units(audio);
        JSONObject closest = null; long distance = Long.MAX_VALUE;
        for (int i = 0; i < units.length(); i++) {
            JSONObject unit = units.getJSONObject(i); if (!unit.optBoolean("timed")) continue;
            if (at >= unit.optLong("start_ms") && at < unit.optLong("end_ms")) return unit;
            long gap = at < unit.optLong("start_ms") ? unit.optLong("start_ms") - at : at >= unit.optLong("end_ms") ? at - unit.optLong("end_ms") : 0;
            if (gap < distance) { distance = gap; closest = unit; }
        }
        return distance <= 15000 ? closest : null;
    }

    static void highlights(AppScreen screen, LinearLayout parent, List<JSONObject> records, TranscriptViews.SeekAction seek) throws Exception {
        parent.addView(screen.title("你标记的重点", 18));
        parent.addView(screen.text("按录音时的标记回听；下方引用保留原句和原始时间。", 12, screen.MUTED));
        int count = 0;
        for (JSONObject audio : records) {
            JSONArray markers = markers(audio);
            for (int i = 0; i < markers.length(); i++) {
                long at = markers.getJSONObject(i).getLong("offset_ms"); JSONObject unit = nearby(audio, at);
                LinearLayout box = screen.inset(parent, screen.TINT);
                box.addView(screen.title("重点 " + (++count) + " · " + (records.size() > 1 ? TranscriptDocument.clock(audio.optLong("created") + at) : PlaybackTimeline.clock(at)), 16));
                if (unit != null) {
                    box.addView(screen.rawText(unit.optString("text"), 15, screen.INK));
                    box.addView(screen.text("附近原句 · " + PlaybackTimeline.clock(unit.optLong("start_ms")) + " – " + PlaybackTimeline.clock(unit.optLong("end_ms")), 12, screen.MUTED));
                } else box.addView(screen.text(audio.optString("transcript").trim().isEmpty() ? "标记已保存。完成转写后可在这里核对附近原句。" : "这个标记附近没有已对齐的原句，可直接回听。", 14, screen.MUTED));
                screen.button(box, "▷ 回听标记", () -> seek.seek(audio, at));
            }
        }
        if (count == 0) parent.addView(screen.text("这组录音还没有标记。手表录音时点击「标记重点」，同步后会出现在这里。", 14, screen.MUTED));
    }

    static void summary(AppScreen screen, LinearLayout parent, List<JSONObject> records, TranscriptViews.SeekAction seek, Runnable analyze) throws Exception {
        parent.addView(screen.title("有来源的总结", 18));
        parent.addView(screen.text("AI 整理 · 可回听原句核对；记忆和待办需要你审核采用。", 12, screen.MUTED));
        Set<String> emitted = new HashSet<>(); boolean found = false, outdated = false;
        for (JSONObject audio : records) {
            JSONObject result = AudioReview.object(audio.optString("analysis"));
            if (result.optString("summary").isEmpty()) continue;
            String identity = result.optJSONArray("sources") == null ? audio.optString("id") : result.optJSONArray("sources").toString();
            if (!emitted.add(identity)) continue;
            try { LifeMemory.checkReading(screen.store, result); } catch (Exception stale) { outdated = true; continue; }
            JSONArray paragraphs = result.optJSONArray("paragraphs"); if (paragraphs == null || paragraphs.length() == 0) { outdated = true; continue; }
            found = true;
            LinearLayout overview = screen.inset(parent, screen.TINT);
            overview.addView(screen.rawText(result.optString("summary"), 16, screen.INK));
            overview.addView(screen.text("来源见下方时间段", 12, screen.SAGE));
            for (int n = 0; n < paragraphs.length(); n++) {
                JSONObject paragraph = paragraphs.getJSONObject(n); JSONArray units = paragraph.optJSONArray("units");
                if (units == null || units.length() == 0) continue;
                LinearLayout box = screen.inset(parent, screen.PAPER);
                box.addView(screen.rawTitle(paragraph.optString("title"), 16));
                box.addView(screen.rawText(paragraph.optString("summary"), 14, screen.INK));
                LinkedHashMap<String, JSONObject> first = new LinkedHashMap<>(), last = new LinkedHashMap<>();
                for (int u = 0; u < units.length(); u++) { JSONObject unit = units.getJSONObject(u); String source = unit.optString("source_id"); if (!first.containsKey(source)) first.put(source, unit); last.put(source, unit); }
                for (String source : first.keySet()) {
                    JSONObject unit = first.get(source), end = last.get(source), record;
                    try { record = screen.store.audio(source); } catch (Exception missing) { box.addView(screen.text("来源已不可用", 12, screen.MUTED)); continue; }
                    if (!unit.optBoolean("timed")) { screen.button(box, "查看来源原文 · 时间未对齐", () -> screen.openRecord(source)); continue; }
                    String range = PlaybackTimeline.clock(unit.optLong("start_ms")) + " – " + PlaybackTimeline.clock(end.optLong("end_ms"));
                    if (first.size() > 1 || records.size() > 1) range = TranscriptDocument.clock(record.optLong("created") + unit.optLong("start_ms")) + " – " + TranscriptDocument.clock(record.optLong("created") + end.optLong("end_ms"));
                    screen.button(box, "▷ 来源 " + range, () -> seek.seek(record, unit.optLong("start_ms")));
                }
            }
        }
        if (outdated) parent.addView(screen.text("部分总结的原文或事件已更新，重新整理后可查看最新结果。", 13, screen.MUTED));
        if (!found) parent.addView(screen.text("尚无可核对的总结。转写完成后，可按原句提取要点、记忆和待办。", 14, screen.MUTED));
        screen.button(parent, found ? "重新整理总结" : "整理这组原文", analyze);
    }

    private static int priority(String status) { return status.equals("running") ? 4 : status.equals("queued") ? 3 : status.equals("failed") || status.equals("uncertain") ? 2 : status.equals("cancelled") ? 1 : 0; }

    static void processing(AppScreen screen, LinearLayout parent, List<JSONObject> records, boolean transcription, Runnable refresh) throws Exception {
        JSONObject latest = null; int activeCount = 0; Set<String> seenJobs = new HashSet<>();
        for (JSONObject audio : records) {
            JSONArray jobs = transcription
                ? screen.store.rows("SELECT * FROM jobs WHERE source=? AND type IN ('asr','local_asr') ORDER BY created DESC LIMIT 1", audio.optString("id"))
                : screen.store.rows("SELECT * FROM jobs WHERE (source=? OR input LIKE ?) AND type='analysis' ORDER BY created DESC LIMIT 1", audio.optString("id"), "%" + audio.optString("id") + "%");
            if (jobs.length() == 0) continue; JSONObject job = jobs.getJSONObject(0); if (!seenJobs.add(job.optString("id"))) continue;
            if (priority(job.optString("status")) >= 3) activeCount++;
            if (latest == null || priority(job.optString("status")) > priority(latest.optString("status")) || priority(job.optString("status")) == priority(latest.optString("status")) && job.optLong("created") > latest.optLong("created")) latest = job;
        }
        if (latest == null || latest.optString("status").equals("done")) return;
        String state = latest.optString("status"), subject = transcription ? "转写" : "总结";
        LinearLayout status = screen.inset(parent, screen.TINT);
        if (state.equals("queued") || state.equals("running")) {
            status.addView(screen.text(subject + (state.equals("queued") ? "排队中" : "处理中") + (activeCount > 1 ? " · " + activeCount + " 项任务" : "") + " · 原音仍可回听", 14, screen.SAGE));
            if (state.equals("running")) { ProgressBar bar = screen.progressBar(status); screen.progress(bar, latest.optLong("progress_done"), latest.optLong("progress_total")); }
        } else {
            status.addView(screen.text(subject + (state.equals("cancelled") ? "已取消" : "未完成") + " · 原音与已有文字保留", 14, screen.ACCENT));
            if (!latest.optString("error").isEmpty()) status.addView(screen.rawText(latest.optString("error"), 12, screen.MUTED));
        }
        LinearLayout controls = screen.row(); status.addView(controls); screen.button(controls, "刷新结果", refresh);
        screen.button(controls, state.equals("queued") || state.equals("running") ? "查看进度" : "查看并重试", () -> screen.startActivity(new Intent(screen, TaskCenterActivity.class)));
    }
}
