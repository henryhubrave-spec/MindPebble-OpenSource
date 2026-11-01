package org.diy.liferecorder;
import org.json.*;

/** Optional strict JSON Schema transport for providers that advertise support. */
final class AgentSchemas {
    private static JSONObject string()throws JSONException{return new JSONObject().put("type","string");}
    private static JSONObject array(JSONObject item)throws JSONException{return new JSONObject().put("type","array").put("items",item);}
    private static JSONObject object(String[] keys,JSONObject... values)throws JSONException{JSONObject props=new JSONObject();JSONArray required=new JSONArray();for(int i=0;i<keys.length;i++){props.put(keys[i],values[i]);required.put(keys[i]);}return new JSONObject().put("type","object").put("properties",props).put("required",required).put("additionalProperties",false);}
    static JSONObject extraction()throws JSONException{return object(new String[]{"facts","paragraphs"},array(object(new String[]{"type","text","unit_id","quote"},string().put("enum",new JSONArray().put("fact").put("activity").put("topic").put("decision").put("commitment").put("idea").put("task").put("self_report")),string(),string(),string())),array(object(new String[]{"unit_ids","action","event_id","content_kind"},array(string()),string().put("enum",new JSONArray().put("CONTINUE").put("NEW_EVENT").put("CLOSE_EVENT").put("SPLIT")),string(),string().put("enum",new JSONArray().put("meaningful").put("noise").put("small_talk").put("uncertain")))));}
    static JSONObject summary()throws JSONException{return object(new String[]{"summary","paragraphs","context_review","recovery_hint","audio_review"},string(),array(object(new String[]{"title","summary"},string(),string())),string(),string(),object(new String[]{"classification","suggest_delete","reason"},string().put("enum",new JSONArray().put("noise_or_unusable").put("meaningful").put("uncertain")),new JSONObject().put("type","boolean"),string()));}
    static JSONObject review()throws JSONException{return object(new String[]{"claims","questions"},array(object(new String[]{"section","kind","text","evidence_ids"},string(),string(),string(),array(string()))),array(string()));}
}
