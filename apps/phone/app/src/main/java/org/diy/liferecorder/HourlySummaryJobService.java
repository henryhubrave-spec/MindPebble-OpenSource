package org.diy.liferecorder;

import android.app.job.*;
import android.content.ContentValues;
import org.json.*;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android owns the wakeup; no exact alarm, polling service or microphone is needed. */
public final class HourlySummaryJobService extends JobService {
    private static final AtomicBoolean BUSY=new AtomicBoolean();private final java.util.Set<Integer> stopped=java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    public boolean onStartJob(JobParameters params){if(!BUSY.compareAndSet(false,true))return false;stopped.remove(params.getJobId());new Thread(()->{
        boolean more=false;try(RecordStore db=new RecordStore(this)){
            JSONObject provider=new ProviderClient(this).snapshot("llm");if(!HourlyAutomation.permitted(this,provider)){HourlyAutomation.disable(this);return;}
            more=drain(db,()->stopped.contains(params.getJobId()),System.currentTimeMillis(),ZoneId.systemDefault());
        }catch(Exception e){getSharedPreferences("understanding",0).edit().putString("hourly_schedule_error","逐小时总结暂未完成，请打开页面查看状态").apply();}
        finally{BUSY.set(false);if(!stopped.contains(params.getJobId()))jobFinished(params,false);if(more)new android.os.Handler(getMainLooper()).postDelayed(()->HourlyAutomation.schedule(this,true),1000);UnderstandingAutomation.ready();}
    },"hourly-summary-worker").start();return true;}
    public boolean onStopJob(JobParameters params){stopped.add(params.getJobId());return true;}
    static boolean drain(RecordStore db,ProviderClient.Cancel stop,long now,ZoneId zone)throws Exception {
        JSONObject config=new ProviderClient(db.context).snapshot("llm");if(!HourlyAutomation.permitted(db.context,config))return false;
        HourlySummaries.enqueue(db,config,now,zone,12);int processed=0;
        while(!stop.cancelled()&&processed<4){JSONArray rows=db.rows("SELECT * FROM jobs WHERE type='hourly_summary' AND status='queued' ORDER BY created,rowid LIMIT 1");if(rows.length()==0)break;
            JSONObject job=rows.getJSONObject(0),input=new JSONObject(job.getString("input"));String id=job.getString("id");if(!input.getJSONObject("hourly_snapshot").getString("zone").equals(zone.getId())){db.update("jobs",id,"status","cancelled");db.update("jobs",id,"error","手机时区已改变，按当前时区重新整理");continue;}ContentValues values=new ContentValues();values.put("status","running");if(db.getWritableDatabase().update("jobs",values,"id=? AND status='queued'",new String[]{id})!=1)continue;processed++;
            ProviderClient.Cancel cancel=()->{try{return stop.cancelled()||!HourlyAutomation.permitted(db.context,input.getJSONObject("provider"))||!db.rows("SELECT status FROM jobs WHERE id=?",id).getJSONObject(0).optString("status").equals("running");}catch(Exception e){return true;}};
            try{JSONObject result=HourlySummaries.run(db,new ProviderClient(db.context),input,cancel,id);if(cancel.cancelled())throw new java.io.IOException("处理已停止");HourlySummaries.save(db,id,input,result);}
            catch(Exception e){ContentValues failure=new ContentValues();failure.put("status",stop.cancelled()?"uncertain":cancel.cancelled()?"cancelled":"failed");failure.put("error",stop.cancelled()?"系统中断处理；服务可能已收到请求，请确认后重试":e instanceof java.io.IOException?e.getMessage():"服务返回内容无法核对，请重试或检查模型配置");db.getWritableDatabase().update("jobs",failure,"id=? AND status='running'",new String[]{id});}
        }
        return !stop.cancelled()&&HourlyAutomation.permitted(db.context,config)&&(db.rows("SELECT id FROM jobs WHERE type='hourly_summary' AND status='queued' LIMIT 1").length()>0||db.rows("SELECT id FROM hourly_summaries WHERE zone=? AND dirty=1 AND end<=? LIMIT 1",zone.getId(),String.valueOf(now)).length()>0);
    }
}
