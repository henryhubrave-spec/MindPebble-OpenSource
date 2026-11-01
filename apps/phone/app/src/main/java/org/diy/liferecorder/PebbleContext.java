package org.diy.liferecorder;
import org.json.*;import java.util.*;
/** Latest valid values and latest sampling outcomes are tracked separately. */
final class PebbleContext {
 final Map<String,JSONObject> values=new HashMap<>(),latest=new HashMap<>();
 JSONObject capabilities;
 PebbleContext(RecordStore store,String source){try{JSONArray rows=source==null?store.rows("SELECT payload FROM context ORDER BY created DESC LIMIT 64"):store.rows("SELECT payload FROM context WHERE source=? ORDER BY created",source);for(int n=0;n<rows.length();n++)for(String line:rows.getJSONObject(n).optString("payload").split("\n")){try{accept(new JSONObject(line));}catch(Exception ignored){}}}catch(Exception ignored){}}
 PebbleContext(){}
 static long at(JSONObject r){return r.optLong("observed_at_epoch_ms",r.optLong("at_epoch_ms"));}
 void accept(JSONObject row){String type=row.optString("type");if(type.equals("capabilities")){if(capabilities==null||at(row)>=at(capabilities))capabilities=row;return;}if(!type.matches("heart_rate|light|pressure|steps_counter|device_status"))return;JSONObject previous=latest.get(type);if(previous==null||at(row)>=at(previous))latest.put(type,row);
   double v=row.optDouble("value",Double.NaN);if(!type.equals("device_status")&&(!Double.isFinite(v)||(type.equals("heart_rate")||type.equals("pressure"))&&v<=0||v<0))return;
   previous=values.get(type);if(previous==null||at(row)>=at(previous))values.put(type,row);
 }
 String value(String key){JSONObject row=values.get(key);return row==null?"—":String.format(Locale.CHINA,"%.0f",row.optDouble("value"));}
 String status(String key){JSONObject row=values.get(key),last=latest.get(key);String missing=last==null?"":last.optString("missing_reason");String reason=missing.equals("permission_not_granted")?"手表未授权身体传感器":missing.equals("no_valid_events")?"本次未采到有效值，请检查佩戴":missing.equals("sensor_unavailable")?"设备无此传感器":"尚未收到有效读数";
   if(row==null){if(last==null&&capabilities!=null){String sensor=key.equals("heart_rate")?"heart":key;if(!capabilities.optBoolean(sensor+"_available",true))return "设备无此传感器";JSONObject policy=capabilities.optJSONObject("policy");if(policy!=null&&!policy.optBoolean(sensor))return "采样未开启";}return reason;}
   String when=at(row)>0?new java.text.SimpleDateFormat("M/d HH:mm",Locale.CHINA).format(new Date(at(row))):"时间未知";
   return "最近有效 · "+when+(last!=null&&at(last)>at(row)&&!missing.isEmpty()?"；"+reason:System.currentTimeMillis()-at(row)>900000?" · 较早读数":" · 离散观察");
 }
 String chips(){return "♡ "+value("heart_rate")+" bpm    ☼ "+value("light")+" Lux    ↕ "+value("pressure")+" hPa";}
 String timestamp(){long at=0;for(JSONObject row:values.values())at=Math.max(at,at(row));return at==0?"尚无读数":new java.text.SimpleDateFormat("M/d HH:mm",Locale.CHINA).format(new Date(at))+" 观察";}
 String insight(){StringBuilder out=new StringBuilder();String[] keys={"heart_rate","light","pressure"},names={"心率","光照","气压"},units={"bpm","Lux","hPa"};for(int i=0;i<keys.length;i++){if(i>0)out.append('\n');out.append(names[i]).append("：").append(value(keys[i]));if(!value(keys[i]).equals("—"))out.append(' ').append(units[i]);out.append(" · ").append(status(keys[i]));}return out.toString();}
 String battery(){JSONObject r=values.get("device_status");int value=r==null?-1:r.optInt("battery_percent",-1);return value<0?"":" · 上次电量 "+value+"%";}
}
