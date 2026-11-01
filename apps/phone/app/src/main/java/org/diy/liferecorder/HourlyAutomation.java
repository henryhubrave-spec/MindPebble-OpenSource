package org.diy.liferecorder;

import android.app.job.*;
import android.content.*;
import org.json.*;

/** A separate provider-bound opt-in. Only transcript text and source times leave the phone. */
final class HourlyAutomation {
    static boolean enabled(Context c){return c.getSharedPreferences("understanding",0).getBoolean("hourly_enabled",false);}
    static boolean permitted(Context c,JSONObject provider){try{return enabled(c)&&UnderstandingAutomation.binding(provider).equals(c.getSharedPreferences("understanding",0).getString("hourly_provider",""))&&UnderstandingAutomation.binding(provider).equals(UnderstandingAutomation.binding(new ProviderClient(c).snapshot("llm")));}catch(Exception e){return false;}}
    static void schedule(Context c,boolean soon){try{
        JobScheduler scheduler=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(!enabled(c)){scheduler.cancel(84);scheduler.cancel(85);return;}
        ComponentName service=new ComponentName(c,HourlySummaryJobService.class);
        if(scheduler.getPendingJob(84)==null)scheduler.schedule(new JobInfo.Builder(84,service).setPeriodic(15*60*1000).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).setPersisted(true).build());
        if(soon&&scheduler.getPendingJob(85)==null)scheduler.schedule(new JobInfo.Builder(85,service).setMinimumLatency(30000).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build());
    }catch(Exception e){android.util.Log.w("HourlySummary","Unable to schedule hourly work",e);c.getSharedPreferences("understanding",0).edit().putString("hourly_schedule_error","系统调度暂不可用，重新打开页面后会恢复").apply();}}
    static void disable(Context c)throws Exception {
        c.getSharedPreferences("understanding",0).edit().putBoolean("hourly_enabled",false).commit();
        try(RecordStore db=new RecordStore(c)){db.getWritableDatabase().execSQL("UPDATE jobs SET status='cancelled',error='自动逐小时总结已关闭' WHERE type='hourly_summary' AND status IN ('queued','running')");}schedule(c,false);
    }
    static void toggle(AppScreen screen,Runnable changed){
        if(enabled(screen)){screen.background(()->disable(screen),changed);return;}
        try{JSONObject provider=new ProviderClient(screen).snapshot("llm");String host=new java.net.URI(provider.getString("endpoint")).getHost();if(host==null||provider.optString("model").trim().isEmpty())throw new java.io.IOException("请先在分析 API 中填写服务地址与模型");
            new LocalDialog(screen).setTitle("自动逐小时总结").setMessage("接收方："+host+"\n模型："+provider.optString("model")+"\n\n按手机本地时间，在每小时结束后整理已有的逐句转写，并补齐历史时段。新转写与校对会更新对应小时。\n\n只发送该小时的文字、说话人和来源时间；不发送原音与长期记忆。每小时通常一次请求，长原文分批，可能产生费用。离线或省电时延后；失败后由你重试。可随时关闭，更换服务后需重新开启。")
                .setPositiveButton("开启自动总结",(d,n)->{screen.getSharedPreferences("understanding",0).edit().putBoolean("hourly_enabled",true).putString("hourly_provider",UnderstandingAutomation.binding(provider)).remove("hourly_schedule_error").commit();schedule(screen,true);changed.run();}).setNegativeButton("取消",null).show();
        }catch(Exception e){screen.notice.setText(e.getMessage());}
    }
}
