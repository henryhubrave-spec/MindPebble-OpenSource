package org.diy.liferecorder;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;

/** Synthetic time-mapping checks; no files, microphone, network or private recordings are touched. */
final class DetailPlaybackTests {
    private static int checks;
    private static void check(boolean value, String message) throws Exception { if (!value) throw new IOException(message); checks++; }

    static int run() throws Exception {
        checks = 0;
        JSONObject original = new JSONObject().put("id", "synthetic-detail").put("duration", 60000).put("source_retained", 1);
        check(PlaybackTimeline.duration(original) == 60000, "Original playback retains full duration");
        check(PlaybackTimeline.toPlayback(original, 15000, false) == 15000, "Original marker offset is not shifted");
        check(PlaybackTimeline.toSource(original, 15000, false) == 15000, "Original playback reports original time");
        JSONArray ranges = new JSONArray()
            .put(range(10000, 20000, 0, 10000))
            .put(range(30000, 45000, 10000, 25000));
        JSONObject retained = new JSONObject(original.toString()).put("source_retained", 0).put("vad_map", new JSONObject().put("ranges", ranges).toString());
        check(PlaybackTimeline.duration(retained) == 25000, "Retained duration uses compacted audio, not elapsed source duration");
        check(PlaybackTimeline.toPlayback(retained, 10000, true) == 0, "First retained source sample maps to playback zero");
        check(PlaybackTimeline.toPlayback(retained, 19999, true) == 9999, "Subsecond position before removed silence remains precise");
        check(PlaybackTimeline.toPlayback(retained, 20000, true) == -1, "Removed silence boundary is unavailable, not silently moved");
        check(PlaybackTimeline.toPlayback(retained, 25000, true) == -1, "Marker in removed silence cannot play unrelated sound");
        check(PlaybackTimeline.toPlayback(retained, 30000, true) == 10000, "Second speech range preserves original anchor");
        check(PlaybackTimeline.toSource(retained, 9999, true) == 19999, "Current sentence before speech boundary uses source time");
        check(PlaybackTimeline.toSource(retained, 10000, true) == 30000, "Current sentence after speech boundary jumps across omitted silence");
        check(PlaybackTimeline.toSource(retained, 25000, true) == 45000, "Playback end maps to last retained source end");
        JSONObject noSpeech = new JSONObject(original.toString()).put("source_retained", 0).put("vad_map", "{\"no_speech_detected\":true,\"ranges\":[]}");
        check(PlaybackTimeline.duration(noSpeech) == 0 && PlaybackTimeline.toPlayback(noSpeech, 0, true) == -1, "Silence-only retained record has no playable anchor");
        check(PlaybackTimeline.toSource(noSpeech, 0, true) == -1, "Silence-only record does not invent a source position");
        check(PlaybackTimeline.toPlayback(original, -1, false) == -1, "Negative original positions are invalid");
        check(PlaybackTimeline.clock(3661000).equals("61:01"), "Long recordings retain complete minute count");
        JSONArray markers = new JSONArray().put(new JSONObject().put("offset_ms", 5000)).put(new JSONObject().put("offset_ms", 1000))
            .put(new JSONObject().put("offset_ms", 5000)).put(new JSONObject().put("offset_ms", -1)).put(new JSONObject().put("offset_ms", 60001)).put("bad");
        original.put("metadata", new JSONObject().put("markers", markers).toString());
        JSONArray safeMarkers = DetailEvidence.markers(original);
        check(safeMarkers.length() == 2 && safeMarkers.getJSONObject(0).getLong("offset_ms") == 1000 && safeMarkers.getJSONObject(1).getLong("offset_ms") == 5000, "Markers are ordered, deduplicated, and constrained to original duration");
        JSONArray segments = new JSONArray().put(segment(0, 10000, "第一句原文")).put(segment(10000, 20000, "第二句原文"));
        original.put("speech_json", new JSONObject().put("segments", segments).toString());
        check(DetailEvidence.nearby(original, 10000).getString("text").equals("第二句原文"), "Exact sentence boundary chooses containing sentence, not previous sentence");
        check(DetailEvidence.nearby(original, 25000).getString("text").equals("第二句原文"), "Nearby marker context is a real original sentence");
        check(DetailEvidence.nearby(original, 40000) == null, "Distant marker has no fabricated nearby quotation");
        check(DetailEvidence.markers(new JSONObject().put("metadata", "malformed")).length() == 0, "Malformed optional metadata cannot hide the recording detail");
        return checks;
    }
    private static JSONObject range(long start, long end, long outputStart, long outputEnd) throws Exception { return new JSONObject().put("source_start_sample", start * 16).put("source_end_sample", end * 16).put("output_start_sample", outputStart * 16).put("output_end_sample", outputEnd * 16); }
    private static JSONObject segment(long start, long end, String text) throws Exception { return new JSONObject().put("start_ms", start).put("end_ms", end).put("speaker", "测试说话人").put("text", text); }
}
