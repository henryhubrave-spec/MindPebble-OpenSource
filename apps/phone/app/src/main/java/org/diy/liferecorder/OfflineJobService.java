package org.diy.liferecorder;
import android.app.job.*;
/** Background automatic work; visible user requests use the dedicated foreground worker. */
public final class OfflineJobService extends JobService {
 static volatile boolean running;static volatile String status="暂无离线任务";private volatile boolean stopped;
 public boolean onStartJob(JobParameters params){if(running)return false;running=true;stopped=false;new Thread(()->{boolean retry=true;try{if(params.getJobId()==82)OfflineScheduler.enqueueMissing(this);retry=OfflineProcessor.drain(this,false,()->stopped);}catch(Exception ignored){status="等待恢复离线队列";}finally{running=false;if(!stopped){jobFinished(params,retry);if(!retry)new android.os.Handler(getMainLooper()).postDelayed(()->OfflineScheduler.schedule(this),500);}}},"scheduled-offline-speech").start();return true;}
 public boolean onStopJob(JobParameters params){stopped=true;return true;}
}
