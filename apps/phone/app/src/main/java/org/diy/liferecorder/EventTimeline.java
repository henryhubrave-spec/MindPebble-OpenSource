package org.diy.liferecorder;
import java.util.*;
import org.json.*;

/** Bounded pending recording groups; semantic events come from validated event_units. */
final class EventTimeline {
    static final class Item {
        JSONObject event;ArrayList<JSONObject> records=new ArrayList<>();long start;PebbleContext context;String preview="";
    }
    static ArrayList<Item> items(RecordStore db,JSONArray rows)throws Exception{return items(db,rows,false);}
    static ArrayList<Item> items(RecordStore db,JSONArray rows,boolean raw)throws Exception{
        Map<String,JSONObject> selected=new HashMap<>();Map<String,JSONArray> unitsBySource=new HashMap<>();Map<String,Set<String>> valid=new HashMap<>();
        ArrayList<Item> out=new ArrayList<>();
        for(int i=0;i<rows.length();i++){JSONObject audio=rows.getJSONObject(i);String id=audio.getString("id");selected.put(id,audio);JSONArray units=TranscriptDocument.units(audio);unitsBySource.put(id,units);Set<String> keys=new HashSet<>();for(int n=0;n<units.length();n++)keys.add(units.getJSONObject(n).getString("unit_id"));valid.put(id,keys);}
        if(raw){for(int i=0;i<rows.length();i++){JSONObject audio=rows.getJSONObject(i);Item item=new Item();item.records.add(audio);item.start=audio.optLong("created");item.preview=TranscriptDocument.text(unitsBySource.get(audio.getString("id")));out.add(item);}out.sort((a,b)->Long.compare(b.start,a.start));return out;}
        if(rows.length()==0)return out;
        Map<String,Set<String>> covered=new HashMap<>();Map<String,Item> events=new HashMap<>();
        // One indexed join replaces one query per recording. All parsing runs on the loader thread.
        StringBuilder placeholders=new StringBuilder();for(String source:selected.keySet()){if(placeholders.length()>0)placeholders.append(',');placeholders.append('?');}
        JSONArray refs=db.rows("SELECT e.*,u.unit_id,u.source,d.payload AS filter_payload,d.user_override FROM life_events e JOIN event_units u ON u.event_id=e.id LEFT JOIN filter_decisions d ON d.event_id=e.id WHERE e.state<>'stale' AND e.id IN (SELECT event_id FROM event_units WHERE source IN ("+placeholders+"))",selected.keySet().toArray(new String[0]));
        for(int i=0;i<refs.length();i++){JSONObject e=refs.getJSONObject(i);String source=e.getString("source"),id=e.getString("id");
            if(!selected.containsKey(source)||!valid.get(source).contains(e.getString("unit_id")))continue;
            covered.computeIfAbsent(source,k->new HashSet<>()).add(e.getString("unit_id"));Item item=events.get(id);if(item==null){item=new Item();item.event=e;item.start=e.getLong("start");events.put(id,item);}if(!item.records.contains(selected.get(source)))item.records.add(selected.get(source));
        }
        for(Item item:events.values())if(!ContentFilter.action(item.event).equals("HIDE"))out.add(item);
        for(int i=0;i<rows.length();i++){JSONObject audio=rows.getJSONObject(i);String id=audio.getString("id");JSONArray units=unitsBySource.get(id);if(!ContentVisibility.reason(audio,units).isEmpty())continue;Set<String> done=covered.get(id);boolean missing=done==null;for(int n=0;n<units.length();n++)if(done==null||!done.contains(units.getJSONObject(n).getString("unit_id")))missing=true;
            if(missing){Item item=new Item();item.records.add(audio);item.start=audio.optLong("created");item.preview=TranscriptDocument.text(unitsBySource.get(audio.getString("id")));out.add(item);}
        }
        out.sort((a,b)->Long.compare(b.start,a.start));return out;
    }
    static LinkedHashMap<String,ArrayList<JSONObject>> group(JSONArray rows)throws Exception{
        ArrayList<JSONObject> sorted=new ArrayList<>();for(int i=0;i<rows.length();i++)sorted.add(rows.getJSONObject(i));sorted.sort((a,b)->Long.compare(a.optLong("created"),b.optLong("created")));
        ArrayList<ArrayList<JSONObject>> groups=new ArrayList<>();ArrayList<JSONObject> current=null;String session="";long end=0,start=0;String day="";
        java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);
        for(JSONObject row:sorted){String s=row.optString("id");try{s=new JSONObject(row.optString("metadata","{}")).optString("session_id",s);}catch(Exception ignored){}
            long at=row.optLong("created");String date=format.format(new Date(at));
            if(current==null||!session.equals(s)||!day.equals(date)||at-end>15*60*1000||at+row.optLong("duration")-start>30*60*1000){current=new ArrayList<>();groups.add(current);session=s;day=date;start=at;end=at;}
            current.add(row);end=Math.max(end,at+row.optLong("duration"));
        }
        LinkedHashMap<String,ArrayList<JSONObject>> result=new LinkedHashMap<>();for(int i=groups.size()-1;i>=0;i--){ArrayList<JSONObject> group=groups.get(i);result.put(group.get(0).getString("id"),group);}return result;
    }
}
