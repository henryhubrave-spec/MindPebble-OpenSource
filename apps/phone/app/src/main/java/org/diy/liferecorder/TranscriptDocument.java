package org.diy.liferecorder;

import org.json.*;
import java.util.*;

/** A derived reading view. Raw ASR and original offsets remain available for correction. */
final class TranscriptDocument {
    static String clean(String text) {
        StringBuilder out=new StringBuilder();
        for(String line:text.replaceAll("<\\|[^|]*\\|>", "").split("\\r?\\n")) {
            String value=line.trim().replaceAll("([。！？!?，,；;])\\1+", "$1");
            if(!value.matches(".*[\\p{L}\\p{N}].*"))continue;
            if(out.length()>0)out.append('\n');out.append(value);
        }
        return out.toString();
    }
    static boolean visible(JSONObject segment) {
        String text=clean(segment.optString("text"));
        if(text.isEmpty())return false;
        if(segment.optBoolean("speaker_confirmed")||segment.optBoolean("text_confirmed"))return true;
        String filler=text.replaceAll("[^\\p{L}\\p{N}]", "");
        if(filler.matches("(?:嗯|呃|啊|额|唔|哦|噢|诶|呜|那个|就是|然后)+")||filler.matches("我{2,}"))return false;
        if(segment.optDouble("no_speech_prob",0)>.9&&segment.optDouble("avg_logprob",0)<-1.5)return false;
        String content=text.replaceAll("[^\\p{L}\\p{N}]", "");
        // Never remove short decisions/negations/names just because they are brief.
        return !(segment.optLong("end_ms")-segment.optLong("start_ms")<=2500
                &&segment.optString("speaker", "身份不确定").equals("身份不确定")
                &&content.matches("[嗯呃啊额唔我的是对]+")&&content.length()<=2);
    }
    static JSONArray units(JSONObject audio)throws JSONException {return units(audio,false);}
    static JSONArray units(JSONObject audio,boolean full)throws JSONException {
        JSONArray out=new JSONArray();JSONObject speech=AudioReview.object(audio.optString("speech_json"));JSONArray segments=speech.optJSONArray("segments");
        if(segments!=null&&segments.length()>0){
            for(int i=0;i<segments.length();i++){JSONObject segment=segments.getJSONObject(i);if(!full&&!visible(segment))continue;
                out.put(new JSONObject().put("unit_id",audio.optString("id")+":"+i).put("source_id",audio.optString("id"))
                    .put("segment_index",i).put("start_ms",segment.optLong("start_ms")).put("end_ms",segment.optLong("end_ms"))
                    .put("speaker",segment.optString("speaker","身份不确定")).put("text",full?segment.optString("text"):clean(segment.optString("text"))).put("timed",true));}
        }else{String text=clean(audio.optString("transcript"));if(!text.isEmpty())out.put(new JSONObject().put("unit_id",audio.optString("id")+":text").put("source_id",audio.optString("id")).put("text",text).put("speaker","身份不确定").put("timed",false));}
        return out;
    }
    static String format(JSONObject audio)throws JSONException {
        JSONArray units=units(audio);StringBuilder out=new StringBuilder();
        for(int i=0;i<units.length();i++){JSONObject u=units.getJSONObject(i);if(out.length()>0)out.append("\n\n");
            out.append(u.optBoolean("timed")?clock(audio.optLong("created")+u.optLong("start_ms"))+" – "+clock(audio.optLong("created")+u.optLong("end_ms")):"时间未对齐")
                .append(" · ").append(u.optString("speaker")).append('\n').append(u.optString("text"));}
        return out.toString();
    }
    static String clock(long at){return new java.text.SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(new Date(at));}
    static String text(JSONArray units)throws JSONException{StringBuilder out=new StringBuilder();for(int i=0;i<units.length();i++){if(out.length()>0)out.append('\n');out.append(units.getJSONObject(i).getString("text"));}return out.toString();}
}
