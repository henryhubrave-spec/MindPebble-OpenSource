package org.diy.liferecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.*;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, revocable permission for future text-only analysis with a fixed provider. */
final class UnderstandingAutomation {
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private static volatile long lastScan;
    private static WeakReference<AppScreen> visible=new WeakReference<>(null);

    static String binding(JSONObject provider){
        return provider.optString("endpoint")+"\n"+provider.optString("model")+"\n"+provider.optLong("revision");
    }

    static boolean permitted(Context context,JSONObject provider){
        SharedPreferences prefs=context.getSharedPreferences("understanding",0);
        return provider!=null&&prefs.getBoolean("auto_events",false)
            &&binding(provider).equals(prefs.getString("auto_provider",""));
    }

    static boolean cancelled(Context context,JSONObject input){
        return input.optBoolean("automatic")&&!permitted(context,input.optJSONObject("provider"));
    }

    static void disable(Context context)throws Exception {
        // Revoke the request guard first, before waiting for the queue's database lock.
        if(!context.getSharedPreferences("understanding",0).edit().putBoolean("auto_events",false).commit()){
            throw new IOException("无法保存自动整理设置");
        }
        try(RecordStore db=new RecordStore(context)){
            db.getWritableDatabase().execSQL("UPDATE jobs SET status='cancelled',error='自动整理已关闭' "
                +"WHERE type='analysis' AND automatic=1 AND status IN ('queued','running')");
        }
    }

    static void resume(AppScreen screen){visible=new WeakReference<>(screen);scan(screen);}
    static void pause(AppScreen screen){if(visible.get()==screen)visible=new WeakReference<>(null);}
    static void ready(){
        new Handler(Looper.getMainLooper()).post(()->{
            AppScreen screen=visible.get();
            if(screen!=null&&!screen.isDestroyed())screen.onUnderstandingUpdated();
        });
    }

    static void scan(AppScreen screen){
        if(!screen.getSharedPreferences("understanding",0).getBoolean("auto_events",false)
            ||SystemClock.elapsedRealtime()-lastScan<10000||!BUSY.compareAndSet(false,true))return;
        lastScan=SystemClock.elapsedRealtime();
        WORK.execute(()->{
            try(RecordStore db=new RecordStore(screen)){
                JSONObject provider=new ProviderClient(screen).snapshot("llm");
                if(!permitted(screen,provider)){
                    disable(screen);
                    return;
                }
                JSONArray rows=db.rows("SELECT * FROM audio a WHERE transcript<>'' AND NOT EXISTS"
                    +"(SELECT 1 FROM analysis_claims c WHERE c.source=a.id AND c.revision=a.transcript_revision) "
                    +"ORDER BY created DESC LIMIT 300");
                JSONArray ids=new JSONArray();
                for(EventTimeline.Item item:EventTimeline.items(db,rows)){
                    if(item.event==null)for(JSONObject record:item.records)ids.put(record.getString("id"));
                }
                int added=ids.length();
                if(added>0&&permitted(screen,new ProviderClient(screen).snapshot("llm"))){
                    db.addJob("","analysis_batch",new JSONObject().put("sources",ids)
                        .put("provider",provider).put("automatic",true));
                }
                boolean queued=db.rows("SELECT id FROM jobs WHERE type='analysis' AND status='queued' LIMIT 1").length()>0;
                screen.runOnUiThread(()->{
                    if(visible.get()!=screen||screen.isDestroyed())return;
                    if(queued){screen.startJobs();if(added>0)screen.notice.setText("已自动加入语义事件整理队列");}
                });
            }catch(Exception error){
                screen.runOnUiThread(()->{
                    if(visible.get()==screen&&!screen.isDestroyed())screen.notice.setText("自动整理暂未完成，可在任务中心查看或重试");
                });
            }finally{BUSY.set(false);}
        });
    }

    static void toggle(AppScreen screen){
        SharedPreferences prefs=screen.getSharedPreferences("understanding",0);
        if(prefs.getBoolean("auto_events",false)){
            screen.background(()->disable(screen),screen::recreate);
            return;
        }
        try{
            JSONObject provider=new ProviderClient(screen).snapshot("llm");
            String host=new URI(provider.getString("endpoint")).getHost();
            if(host==null||provider.optString("model").isEmpty())throw new IOException("请先配置分析 API");
            new LocalDialog(screen).setTitle("自动整理已转写原文")
                .setMessage("接收方："+host+"\n模型："+provider.optString("model")
                    +"\n开启后自动将待整理原文、时间、说话人、相邻录音的少量边界文字及已确认的相关记忆发送到此服务。"
                    +"每批包含提取、过滤复核和总结，通常三次请求，可能产生费用。原音保留在本机；可随时关闭。更换服务配置后需重新开启。")
                .setPositiveButton("同意并开启",(dialog,which)->{
                    prefs.edit().putBoolean("auto_events",true).putString("auto_provider",binding(provider)).apply();
                    lastScan=0;scan(screen);screen.recreate();
                }).setNegativeButton("取消",null).show();
        }catch(Exception error){screen.notice.setText(error.getMessage());}
    }
}
