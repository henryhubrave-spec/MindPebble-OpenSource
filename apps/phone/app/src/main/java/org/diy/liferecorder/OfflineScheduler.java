package org.diy.liferecorder;

import android.app.job.*;
import android.content.*;
import org.json.*;

/** Local-only jobs use a separate executor; no provider job is ever implicitly started. */
final class OfflineScheduler {
    static void enqueue(RecordStore store,String id)throws Exception{
        OfflinePacks packs=new OfflinePacks(store.context);String selected=packs.selected();
        if(!packs.automatic()||selected.isEmpty()||!packs.ready(selected))return;
        android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{
            JSONObject audio=store.audio(id);
            if(audio.optString("transcript").isEmpty()&&!audio.optString("path").isEmpty()&&store.rows("SELECT id FROM jobs WHERE source=? AND type='local_asr' AND status<>'cancelled'",id).length()==0)
                store.addJob(id,"local_asr",new JSONObject().put("pack",selected).put("automatic",true));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    static void enqueueMissing(Context context)throws Exception{try(RecordStore store=new RecordStore(context)){JSONArray rows=store.rows("SELECT id FROM audio WHERE transcript='' AND path<>'' AND NOT EXISTS (SELECT 1 FROM jobs WHERE jobs.source=audio.id AND jobs.type='local_asr' AND jobs.status<>'cancelled') ORDER BY created LIMIT 500");for(int i=0;i<rows.length();i++)enqueue(store,rows.getJSONObject(i).getString("id"));}schedule(context);}
    static void cancelAutomatic(Context context)throws Exception{try(RecordStore db=new RecordStore(context)){db.getWritableDatabase().execSQL("UPDATE jobs SET status='cancelled',error='自动处理已关闭' WHERE type='local_asr' AND status IN ('queued','running') AND input LIKE '%\"automatic\":true%'");}schedule(context);}
    static void schedule(Context context){try{
        OfflinePacks packs=new OfflinePacks(context);JobScheduler jobs=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(context.getSharedPreferences("offline-scheduling",0).getBoolean("paused",false)){jobs.cancel(81);jobs.cancel(82);return;}
        JobInfo periodic=jobs.getPendingJob(82);
        if(packs.automatic()&&(periodic==null||periodic.isRequireCharging()!=packs.chargingOnly()))jobs.schedule(new JobInfo.Builder(82,new ComponentName(context,OfflineJobService.class)).setPeriodic(15*60*1000).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).setRequiresCharging(packs.chargingOnly()).setPersisted(true).build());else if(!packs.automatic())jobs.cancel(82);
        if(OfflineJobService.running||OfflineProcessor.busy()||OfflineRunService.running)return;
        try(RecordStore db=new RecordStore(context)){if(db.rows("SELECT id FROM jobs WHERE type='local_asr' AND status='queued' LIMIT 1").length()==0){jobs.cancel(81);return;}
            boolean manual=db.rows("SELECT id FROM jobs WHERE type='local_asr' AND status='queued' AND input LIKE '%\"automatic\":false%' LIMIT 1").length()>0;
            boolean charging=!manual&&packs.chargingOnly();JobInfo existing=jobs.getPendingJob(81);if(existing!=null&&existing.isRequireCharging()==charging)return;
            int scheduled=jobs.schedule(new JobInfo.Builder(81,new ComponentName(context,OfflineJobService.class)).setMinimumLatency(1000).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).setRequiresCharging(charging).setBackoffCriteria(60000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
            context.getSharedPreferences("offline-scheduling",0).edit().putString("last",scheduled==JobScheduler.RESULT_SUCCESS?"已提交系统调度":"系统未接收调度，请打开任务中心立即处理").apply();
        }
    }catch(Exception error){context.getSharedPreferences("offline-scheduling",0).edit().putString("last","无法提交系统调度："+error.getClass().getSimpleName()).apply();}}
    static void runNow(Context context,String id)throws Exception{try(RecordStore db=new RecordStore(context)){JSONArray rows=id.isEmpty()?db.rows("SELECT * FROM jobs WHERE type='local_asr' AND status='queued' ORDER BY created LIMIT 500"):db.rows("SELECT * FROM jobs WHERE id=? AND type='local_asr' AND status='queued'",id);if(rows.length()==0)throw new java.io.IOException("没有等待处理的离线任务");for(int n=0;n<rows.length();n++){JSONObject job=rows.getJSONObject(n);JSONObject input=new JSONObject(job.getString("input")).put("automatic",false);db.update("jobs",job.getString("id"),"input",input.toString());TaskProgress.set(db,job.getString("id"),"等待前台离线处理",0,0,"");}}context.getSharedPreferences("offline-scheduling",0).edit().putBoolean("paused",false).apply();context.startForegroundService(new Intent(context,OfflineRunService.class).putExtra("manual_only",true));}
    static void runVisible(Context context){if(context.getSharedPreferences("offline-scheduling",0).getBoolean("paused",false))return;if(OfflineProcessor.busy()||OfflineRunService.running)return;try(RecordStore db=new RecordStore(context)){JSONArray rows=db.rows("SELECT * FROM jobs WHERE type='local_asr' AND status='queued' ORDER BY created LIMIT 100");OfflinePacks packs=new OfflinePacks(context);for(int n=0;n<rows.length();n++){JSONObject job=rows.getJSONObject(n);boolean auto=new JSONObject(job.getString("input")).optBoolean("automatic");if(auto&&!packs.automatic())continue;String waiting=OfflineProcessor.waiting(context,job);if(waiting.startsWith("等待下载")||waiting.startsWith("等待充电")||!OfflineProcessor.hold(context,auto).isEmpty()){TaskProgress.set(db,job.getString("id"),waiting,0,0,"");continue;}context.startForegroundService(new Intent(context,OfflineRunService.class).putExtra("manual_only",false));return;}}catch(Exception ignored){}}

    static void runBackground(Context context){if(context.getSharedPreferences("offline-scheduling",0).getBoolean("paused",false))return;try{if(new OfflinePacks(context).automatic())runVisible(context);}catch(Exception ignored){schedule(context);}}

}
