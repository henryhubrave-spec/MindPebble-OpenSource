package org.diy.liferecorder;
import org.json.*;
/** Presentation filters only. Originals and evidence are retained. */
final class ContentVisibility {
 static String reason(JSONObject audio,JSONArray units){
  JSONObject speech=AudioReview.object(audio.optString("speech_json"));
  if(units.length()>0)return "";
  if(speech.optBoolean("no_speech_detected"))return "未检测到有效语音";
  if(!audio.optString("transcript").trim().isEmpty()||speech.has("segments"))return "纯标点、语气词或无有效内容";
  return "尚未转写";
 }
 static boolean noiseEvent(JSONArray payloads,boolean hasFacts)throws Exception{if(hasFacts||payloads.length()==0)return false;for(int i=0;i<payloads.length();i++)if(!payloads.getJSONObject(i).optString("content_kind").equals("noise"))return false;return true;}
}
