package org.diy.watchrecorder;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Serialized capture with durable intent, bounded retry and explicit user cancellation. */
public class RecordingService extends Service {
    public static final String START="start", PAUSE="pause", STOP="stop", MARK="mark", WAKE_SETTINGS="wake-settings";
    public static volatile boolean alive;
    static final long SEGMENT_MS=600000L, MIN_FREE=64L*1024*1024;
    private HandlerThread thread;
    private Handler worker;
    private PowerManager.WakeLock wakeLock;
    private MediaRecorder recorder;
    private SharedAudioRecorder sharedRecorder;
    private WakeWordEngine wakeEngine;
    private File directory,pending;
    private String state="idle",session,resumeReason="manual",recorderStage="idle";
    private long startedWall,startedElapsed,previousEndedElapsed,gapBeforeMs,ownedEpoch=-1,lastGrowth,lastBytes,lastArmed;
    private int sequence,failures;
    private boolean closing,started;
    private ContextRecorder observations;
    private JSONArray markers=new JSONArray();
    private static final class Hold extends IOException {Hold(String message){super(message);}}
    private final Runnable retry=()->begin(ownedEpoch,"retry");
    private final Runnable rotate=()->{
        if(!started||closing)return;
        if(!CaptureIntent.wanted(this,ownedEpoch))return;
        try{finishSegment("segment");openSegment();}catch(Exception e){interrupted(e);}
    };
    private final Runnable guard=new Runnable(){public void run(){
        if(!started||closing)return;
        try{
            if(!CaptureIntent.wanted(RecordingService.this,ownedEpoch))return;
            checkResources();checkAudioMode();
            // Silence still produces AAC bytes; never treat quietness as a reason to stop.
            long size=pending==null?0:pending.length(),now=SystemClock.elapsedRealtime();
            if(size!=lastBytes){lastBytes=size;lastGrowth=now;}
            if(now-lastGrowth>120000)throw new IOException("录音文件超过两分钟没有写入进展");
            if(sharedRecorder!=null)sharedRecorder.getMaxAmplitude();else recorder.getMaxAmplitude();
            getSharedPreferences("recorder",0).edit().putLong("heartbeat_at",System.currentTimeMillis()).apply();
            if(now-lastArmed>60000){CaptureIntent.arm(RecordingService.this,ownedEpoch,90000);lastArmed=now;}
            worker.postDelayed(this,10000);
        }catch(Exception e){interrupted(e);}
    }};
    @Override public void onCreate(){
        super.onCreate();alive=true;CaptureIntent.migrate(this);directory=new File(getFilesDir(),"recordings");
        thread=new HandlerThread("watch-recorder");thread.start();worker=new Handler(thread.getLooper());
        wakeLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"watchrecorder:capture");wakeLock.setReferenceCounted(false);
        NotificationChannel channel=new NotificationChannel("recording","录音状态",NotificationManager.IMPORTANCE_LOW);channel.setSound(null,null);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);startForeground(1,notification("准备录音"));
        CaptureDiagnostics.event(this,"service_create","mode="+CaptureIntent.mode(this));
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        String action=intent==null?CaptureIntent.RECOVER:intent.getAction();long generation;
        try{
            if(START.equals(action))generation=CaptureIntent.user(this,CaptureIntent.RUNNING);
            else if(PAUSE.equals(action))generation=CaptureIntent.user(this,CaptureIntent.PAUSED);
            else if(STOP.equals(action))generation=CaptureIntent.user(this,CaptureIntent.OFF);
            else generation=intent==null?CaptureIntent.epoch(this):intent.getLongExtra("capture_epoch",CaptureIntent.epoch(this));
        }catch(RuntimeException e){CaptureDiagnostics.event(this,"intent_save_failed",e.getClass().getSimpleName());stopSelf();return START_NOT_STICKY;}
        final long token=generation;final String reason=intent==null?"system_sticky_restart":intent.getStringExtra("resume_reason");
        CaptureDiagnostics.event(this,"service_command",action+" epoch="+token);
        worker.post(()->{
            if(closing)return;
            if(START.equals(action)||CaptureIntent.RECOVER.equals(action)){
                if(!CaptureIntent.wanted(this,token)){if(!started&&!CaptureIntent.mode(this).equals(CaptureIntent.RUNNING))stopIfInactive();return;}
                ownedEpoch=token;
                if(started){CaptureIntent.arm(this,token,90000);return;}
                begin(token,START.equals(action)?"manual":reason==null?"automatic":reason);
            }else if(PAUSE.equals(action)){
                cancelTimers();ownedEpoch=-1;boolean saved=saveQuietly("pause");closeWake();releaseWakeLock();setState("paused",saved?"已暂停 · 不会自动恢复":"已暂停 · 未完成片段已保留");
            }else if(STOP.equals(action)){
                cancelTimers();ownedEpoch=-1;boolean saved=saveQuietly("stop");closeWake();shutdown("idle",saved?"已停止 · 录音已保存":"已停止 · 未完成片段已保留");
            }else if(WAKE_SETTINGS.equals(action)){
                if(started&&CaptureIntent.wanted(this,ownedEpoch)){try{finishSegment("wake_setting");closeWake();openSegment();}catch(Exception e){interrupted(e);}}else {closeWake();if(!CaptureIntent.mode(this).equals(CaptureIntent.RUNNING))stopIfInactive();}
            }else if(MARK.equals(action)&&started&&CaptureIntent.wanted(this,ownedEpoch)){
                try{long at=SystemClock.elapsedRealtime()-startedElapsed;markers.put(new JSONObject().put("offset_ms",at));writeJournal();if(observations!=null)observations.marker(at);}catch(Exception e){CaptureDiagnostics.event(this,"marker_save_failed",e.getClass().getSimpleName());}
            }else if(!started)stopIfInactive();
        });
        return CaptureIntent.mode(this).equals(CaptureIntent.RUNNING)?START_STICKY:START_NOT_STICKY;
    }
    private void stopIfInactive(){if(CaptureIntent.mode(this).equals(CaptureIntent.PAUSED))setState("paused","已暂停 · 不会自动恢复");else shutdown("idle","待开始");}
    private void begin(long token,String reason){
        if(closing||!CaptureIntent.wanted(this,token))return;
        ownedEpoch=token;resumeReason=reason;worker.removeCallbacks(retry);
        try{
            if(!CaptureIntent.permission(this))throw new SecurityException("麦克风权限已关闭");
            checkResources();checkAudioMode();
            if(session==null){SharedPreferences p=getSharedPreferences("recorder",0);session=p.getString("active_session","");if(session.isEmpty()||reason.equals("manual")&&!state.equals("paused")){session=UUID.randomUUID().toString();sequence=0;}else sequence=p.getInt("sequence",0);}
            wakeLock.acquire();openSegment();failures=0;setState("recording","正在记录您的生活");
            getSharedPreferences("recorder",0).edit().putLong("last_resumed_at",System.currentTimeMillis()).commit();
            worker.removeCallbacks(guard);worker.post(guard);CaptureIntent.arm(this,token,90000);lastArmed=SystemClock.elapsedRealtime();
            CaptureDiagnostics.event(this,"capture_started",reason+" sequence="+sequence);
        }catch(Exception e){interrupted(e);}
    }
    private void checkResources()throws IOException{
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建录音目录");
        if(directory.getUsableSpace()<MIN_FREE)throw new Hold("空间不足，等待释放空间后恢复");
        Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(battery!=null){int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);boolean plugged=battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
            if(!plugged&&level>=0&&scale>0&&level*100L<=scale*5L)throw new Hold("电量不高于5%，等待充电后恢复");}
    }
    private void checkAudioMode()throws Hold{
        AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);int mode=audio==null?AudioManager.MODE_NORMAL:audio.getMode();
        if(mode==AudioManager.MODE_IN_CALL||mode==AudioManager.MODE_IN_COMMUNICATION||mode==AudioManager.MODE_RINGTONE)throw new Hold("通话或铃声占用音频，结束后自动恢复");
    }
    private void openSegment()throws Exception{
        checkResources();checkAudioMode();if(!CaptureIntent.wanted(this,ownedEpoch))throw new IOException("记录意图已改变");
        startedWall=System.currentTimeMillis();startedElapsed=0;started=false;
        String stamp=new SimpleDateFormat("yyyyMMdd'T'HHmmssSSSZ",Locale.US).format(new Date(startedWall));
        pending=new File(directory,stamp+"_"+session+"_"+(++sequence)+".m4a.part");markers=new JSONArray();
        getSharedPreferences("recorder",0).edit().putString("active_session",session).putInt("sequence",sequence).commit();writeJournal();
        recorderStage="configure";
        if(WakeWordEngine.enabled(this)){
            if(wakeEngine==null)wakeEngine=new WakeWordEngine(this);else wakeEngine.reset();
            final SharedAudioRecorder[] owner=new SharedAudioRecorder[1];owner[0]=new SharedAudioRecorder(pending.getAbsolutePath(),wakeEngine,e->worker.post(()->{if(sharedRecorder==owner[0]&&started)interrupted(e);}));sharedRecorder=owner[0];
            recorderStage="shared_start";sharedRecorder.start(()->CaptureIntent.wanted(this,ownedEpoch));
        }else{
        recorder=new MediaRecorder();recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setAudioChannels(1);recorder.setAudioSamplingRate(16000);recorder.setAudioEncodingBitRate(32000);recorder.setOutputFile(pending.getAbsolutePath());
        recorder.setOnErrorListener((r,what,extra)->worker.post(()->{if(recorder==r){CaptureDiagnostics.event(this,"media_error","what="+what+" extra="+extra);interrupted(new IOException("录音器错误 "+what+"/"+extra));}}));
        recorderStage="prepare";recorder.prepare();if(!CaptureIntent.wanted(this,ownedEpoch))throw new IOException("记录意图已改变");recorderStage="start";recorder.start();}
        recorderStage="recording";started=true;startedElapsed=SystemClock.elapsedRealtime();gapBeforeMs=previousEndedElapsed==0?0:Math.max(0,startedElapsed-previousEndedElapsed);writeJournal();
        lastBytes=pending.length();lastGrowth=startedElapsed;
        try{if(observations==null)observations=new ContextRecorder(this,worker,pending);else observations.openOutput(pending);}catch(Exception e){CaptureDiagnostics.event(this,"context_unavailable",e.getClass().getSimpleName());}
        worker.removeCallbacks(rotate);worker.postDelayed(rotate,SEGMENT_MS);
    }
    private void writeJournal()throws Exception{
        if(pending==null)return;
        JSONObject journal=new JSONObject().put("session_id",session).put("sequence",sequence).put("started_at_epoch_ms",startedWall).put("gap_before_ms",gapBeforeMs).put("markers",markers).put("resume_reason",resumeReason);
        File target=new File(pending.getPath()+".capture.json"),tmp=new File(target.getPath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(journal.toString().getBytes("UTF-8"));out.getFD().sync();}if(!tmp.renameTo(target))throw new IOException("无法保存采集日志");
    }
    private void finishSegment(String reason)throws Exception{
        if(observations!=null){try{if(reason.equals("segment"))observations.finishOutput();else observations.close();}catch(Exception e){CaptureDiagnostics.event(this,"context_save_error",e.getClass().getSimpleName());}if(!reason.equals("segment"))observations=null;}
        if(recorder==null&&sharedRecorder==null)return;MediaRecorder current=recorder;SharedAudioRecorder shared=sharedRecorder;recorder=null;sharedRecorder=null;boolean wasStarted=started;started=false;
        long ended=System.currentTimeMillis(),elapsed=wasStarted?SystemClock.elapsedRealtime()-startedElapsed:0;
        try{if(wasStarted){recorderStage="stop";if(shared!=null)shared.stop();else current.stop();recorderStage="finalize";}}finally{if(shared!=null)shared.release();else current.release();previousEndedElapsed=SystemClock.elapsedRealtime();}
        if(!wasStarted)return;
        File completed=new File(directory,pending.getName().replace(".part",""));
        if(pending.length()==0||!pending.renameTo(completed))throw new IOException("录音保存失败，保留临时文件");
        JSONObject meta=new JSONObject().put("schema_version",1).put("session_id",session).put("sequence",sequence).put("audio_file",completed.getName()).put("started_at_epoch_ms",startedWall).put("ended_at_epoch_ms",ended).put("capture_elapsed_ms",elapsed).put("requested_codec","aac").put("requested_sample_rate_hz",16000).put("requested_bit_rate",32000).put("channels",1).put("reason",reason).put("markers",markers).put("gap_before_ms",gapBeforeMs).put("resume_reason",resumeReason).put("bytes",completed.length()).put("sync_state","local_only");
        File tmp=new File(directory,completed.getName()+".json.tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(meta.toString(2).getBytes("UTF-8"));out.getFD().sync();}if(!tmp.renameTo(new File(directory,completed.getName()+".json")))throw new IOException("元数据保存失败，音频仍保留");
        new File(pending.getPath()+".capture.json").delete();pending=null;
        getSharedPreferences("recorder",0).edit().putLong("last_saved_at",ended).putInt("sequence",sequence).commit();CaptureDiagnostics.event(this,"segment_saved",reason+" sequence="+sequence+" duration_ms="+elapsed);
        if(WatchLink.paired(this))WatchSyncJob.schedule(this);
    }
    private boolean saveQuietly(String reason){try{finishSegment(reason);return true;}catch(Exception e){CaptureDiagnostics.event(this,"segment_save_failed",reason+" stage="+recorderStage+" "+e.getClass().getSimpleName()+" "+e.getMessage());return false;}}
    private void interrupted(Exception e){
        cancelTimers();CaptureDiagnostics.event(this,"capture_interrupted","stage="+recorderStage+" "+e.getClass().getSimpleName()+" "+e.getMessage());saveQuietly("interrupted");releaseWakeLock();if(wakeEngine!=null)wakeEngine.reset();
        if(!CaptureIntent.wanted(this,ownedEpoch)){stopIfInactive();return;}
        if(e instanceof SecurityException){CaptureIntent.user(this,CaptureIntent.OFF);shutdown("error","麦克风权限已关闭，请授权后重新开始");return;}
        failures++;long delay=e instanceof Hold?10000:CaptureIntent.retryDelay(failures);
        setState("recovering",(e instanceof Hold?e.getMessage():"录音意外中断")+" · 将自动重试");
        getSharedPreferences("recorder",0).edit().putLong("last_interruption_at",System.currentTimeMillis()).putInt("retry_count",failures).commit();
        CaptureDiagnostics.event(this,"retry_scheduled","attempt="+failures+" delay_ms="+delay);worker.postDelayed(retry,delay);CaptureIntent.arm(this,ownedEpoch,Math.max(60000,delay));
    }
    private void closeWake(){if(wakeEngine!=null){wakeEngine.close();wakeEngine=null;}}
    private void cancelTimers(){worker.removeCallbacks(rotate);worker.removeCallbacks(guard);worker.removeCallbacks(retry);}
    private void releaseWakeLock(){if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}
    private void shutdown(String next,String message){closing=true;closeWake();releaseWakeLock();setState(next,message);stopForeground(true);stopSelf();}
    private void setState(String next,String message){state=next;getSharedPreferences("recorder",0).edit().putString("state",next).putString("message",message).commit();if(!closing)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,notification(message));}
    private Notification notification(String message){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,"recording").setSmallIcon(R.drawable.ic_record).setContentTitle("MindPebble").setContentText(WatchLanguage.text(this,message)).setContentIntent(open).setOngoing(true);
        if(!state.equals("paused"))b.addAction(0,WatchLanguage.text(this,"暂停"),command(PAUSE,1));b.addAction(0,WatchLanguage.text(this,"停止并保存"),command(STOP,2));return b.build();
    }
    private PendingIntent command(String action,int id){return PendingIntent.getService(this,id,new Intent(this,RecordingService.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    @Override public void onTaskRemoved(Intent rootIntent){CaptureDiagnostics.event(this,"task_removed","mode="+CaptureIntent.mode(this));if(CaptureIntent.mode(this).equals(CaptureIntent.RUNNING))CaptureIntent.arm(this,CaptureIntent.epoch(this),60000);super.onTaskRemoved(rootIntent);}
    @Override public void onTrimMemory(int level){CaptureDiagnostics.event(this,"memory_trim","level="+level);super.onTrimMemory(level);}
    @Override public void onDestroy(){
        alive=false;boolean expected=closing;CaptureDiagnostics.event(this,"service_destroy","expected="+expected+" mode="+CaptureIntent.mode(this));
        worker.post(()->{cancelTimers();saveQuietly("service_destroyed");closeWake();releaseWakeLock();if(!expected&&CaptureIntent.mode(this).equals(CaptureIntent.RUNNING)){if(!alive)setState("recovering","服务意外退出，等待自动恢复");CaptureIntent.arm(this,CaptureIntent.epoch(this),5000);}closing=true;thread.quitSafely();});super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
