package org.diy.liferecorder;
import org.json.*;import java.util.*;
final class ContextSummary {
    static String describe(RecordStore store,String source)throws Exception{
        JSONArray files=store.rows("SELECT payload FROM context WHERE source=? ORDER BY created",source);Map<String,JSONObject> latest=new LinkedHashMap<>();
        for(int i=0;i<files.length();i++)for(String line:files.getJSONObject(i).getString("payload").split("\n")){
            if(line.trim().isEmpty())continue;JSONObject row=new JSONObject(line);String type=row.optString("type");if(type.equals("light")||type.equals("pressure")||type.equals("heart_rate")||type.equals("steps_counter"))latest.put(type,row);
        }
        if(latest.isEmpty())return "这段记录没有有效环境读数。可在手机开启对应项目，并在下一段录音同步后查看。";
        StringBuilder text=new StringBuilder();for(Map.Entry<String,JSONObject> e:latest.entrySet()){
            JSONObject row=e.getValue();String type=e.getKey(),name=type.equals("light")?"光线":type.equals("pressure")?"气压":type.equals("heart_rate")?"心率":"计步器";
            text.append(name).append("：");if(row.isNull("value")||!row.has("value"))text.append("缺测");else text.append(String.format(Locale.CHINA,"%.1f",row.getDouble("value"))).append(" ").append(type.equals("steps_counter")?"步（自开机累计）":row.optString("unit"));
            if(row.optString("observation_mode").equals("last_known_no_new_event"))text.append("（最近有效值，未收到新事件）");
            long at=row.optLong("observed_at_epoch_ms",row.optLong("at_epoch_ms"));if(at>0)text.append(" · ").append(new java.text.SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(new Date(at)));text.append("\n");
        }
        return text.append("\n这些是离散观察，不代表实时值，也不直接推断压力、情绪、紫外线或晒太阳时长。").toString();
    }
}
