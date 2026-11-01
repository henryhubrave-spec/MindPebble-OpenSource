package org.diy.liferecorder;
import android.content.ContentValues;
/** Durable phase progress. Unknown-length phases are indeterminate, never invented percentages. */
final class TaskProgress {
 static void set(RecordStore db,String id,String stage,long done,long total,String unit){ContentValues v=new ContentValues();v.put("stage",stage);v.put("progress_done",Math.max(0,done));v.put("progress_total",Math.max(0,total));v.put("progress_unit",unit);v.put("updated",System.currentTimeMillis());db.getWritableDatabase().update("jobs",v,"id=? AND (stage<>? OR progress_done<>? OR progress_total<>? OR progress_unit<>?)",new String[]{id,stage,String.valueOf(Math.max(0,done)),String.valueOf(Math.max(0,total)),unit});}
 static String describe(org.json.JSONObject row){long done=row.optLong("progress_done"),total=row.optLong("progress_total");String unit=row.optString("progress_unit"),stage=row.optString("stage");if(total>0)return stage+" · "+Math.min(100,done*100/total)+"% · "+done+" / "+total+" "+unit;return stage.isEmpty()?"等待开始":stage;}
}
