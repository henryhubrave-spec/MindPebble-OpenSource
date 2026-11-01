package org.diy.liferecorder;

import org.json.JSONArray;
import org.json.JSONObject;

/** Converts retained-audio positions to original recording time without inventing missing sound. */
final class PlaybackTimeline {
    private PlaybackTimeline() {}

    static long duration(JSONObject record) {
        if (record.optInt("source_retained", 1) != 0) return Math.max(0, record.optLong("duration"));
        JSONArray ranges = ranges(record);
        if (ranges.length() == 0) return 0;
        JSONObject last = ranges.optJSONObject(ranges.length() - 1);
        return last == null ? 0 : Math.max(0, last.optLong("output_end_sample") / 16);
    }

    static long toPlayback(JSONObject record, long sourceMs, boolean speech) {
        if (sourceMs < 0) return -1;
        if (!speech) return sourceMs;
        JSONArray ranges = ranges(record);
        long sample = sourceMs * 16;
        for (int i = 0; i < ranges.length(); i++) {
            JSONObject range = ranges.optJSONObject(i);
            if (range == null) continue;
            long start = range.optLong("source_start_sample"), end = range.optLong("source_end_sample");
            if (sample >= start && sample < end)
                return (range.optLong("output_start_sample") + sample - start) / 16;
        }
        return -1;
    }

    static long toSource(JSONObject record, long playbackMs, boolean speech) {
        if (!speech) return Math.max(0, playbackMs);
        JSONArray ranges = ranges(record);
        long sample = Math.max(0, playbackMs) * 16;
        for (int i = 0; i < ranges.length(); i++) {
            JSONObject range = ranges.optJSONObject(i);
            if (range == null) continue;
            if (sample < range.optLong("output_end_sample"))
                return (range.optLong("source_start_sample") + Math.max(0, sample - range.optLong("output_start_sample"))) / 16;
        }
        JSONObject last = ranges.length() == 0 ? null : ranges.optJSONObject(ranges.length() - 1);
        return last == null ? -1 : last.optLong("source_end_sample") / 16;
    }

    static JSONArray ranges(JSONObject record) {
        JSONArray ranges = AudioReview.object(record.optString("vad_map")).optJSONArray("ranges");
        return ranges == null ? new JSONArray() : ranges;
    }

    static String clock(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
