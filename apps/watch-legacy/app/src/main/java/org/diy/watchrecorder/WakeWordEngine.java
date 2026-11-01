package org.diy.watchrecorder;

import android.content.*;
import android.os.*;
import android.os.Process;
import com.k2fsa.sherpa.onnx.*;
import java.util.concurrent.ArrayBlockingQueue;

/** Offline KWS consumes copies of the recorder's PCM, never opens a second microphone. */
final class WakeWordEngine implements SharedAudioRecorder.Samples {
    private static final Object NATIVE_ENGINE_LOCK=new Object();
    static final String PHRASE="你好小石头";
    private final Context context;private final ArrayBlockingQueue<float[]> queue=new ArrayBlockingQueue<>(25);
    private final Thread thread;private volatile boolean running=true,reset,ready;private int dropped;
    static boolean enabled(Context c){return c.getSharedPreferences("wake-word",0).getBoolean("enabled",false);}
    WakeWordEngine(Context c){context=c.getApplicationContext();status("loading");thread=new Thread(this::run,"wake-word");thread.start();}
    private void status(String value){if(!running&&!value.equals("off"))return;context.getSharedPreferences("wake-word",0).edit().putString("status",value).apply();}
    public void accept(short[] pcm,int length){if(!running||!ready)return;float[] frame=new float[length];for(int i=0;i<length;i++)frame[i]=pcm[i]/32768f;
        if(!queue.offer(frame)){queue.clear();reset=true;if(++dropped>=3){status("overloaded");running=false;CaptureDiagnostics.event(context,"wake_disabled","inference backlog; recording continues");thread.interrupt();}}
    }
    private void run(){synchronized(NATIVE_ENGINE_LOCK){if(running)runEngine();}}
    private void runEngine(){
        KeywordSpotter spotter=null;OnlineStream stream=null;Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        try{
            KeywordSpotterConfig config=new KeywordSpotterConfig();OnlineModelConfig model=config.getModelConfig();String base="wakeword/";
            model.getTransducer().setEncoder(base+"encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx");model.getTransducer().setDecoder(base+"decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx");model.getTransducer().setJoiner(base+"joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx");model.setTokens(base+"tokens.txt");model.setNumThreads(1);model.setProvider("cpu");config.setKeywordsFile("wakeword/keywords.txt");config.setKeywordsScore(1.5f);config.setKeywordsThreshold(.5f);config.setNumTrailingBlanks(2);
            spotter=new KeywordSpotter(context.getAssets(),config);stream=spotter.createStream("");if(!running)return;queue.clear();ready=true;status("ready");CaptureDiagnostics.event(context,"wake_ready","wenetspeech-3.3M-int8");long last=-10001;
            while(running){float[] frame=queue.take();if(reset){spotter.reset(stream);reset=false;}stream.acceptWaveform(frame,16000);
                while(spotter.isReady(stream)){spotter.decode(stream);String word=spotter.getResult(stream).getKeyword();if(!word.isEmpty()){
                    spotter.reset(stream);long now=SystemClock.elapsedRealtime();if(now-last>10000){last=now;activate(context);}break;
                }}
            }
        }catch(InterruptedException ignored){}catch(Exception|LinkageError|OutOfMemoryError e){status("error");CaptureDiagnostics.event(context,"wake_error",e.getClass().getSimpleName());}
        finally{running=false;ready=false;if(stream!=null)stream.release();if(spotter!=null)spotter.release();queue.clear();}
    }
    static void activate(Context c){
        if(!enabled(c)||!CaptureIntent.mode(c).equals(CaptureIntent.RUNNING)||!RecordingService.alive)return;
        try{
            Intent open=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("voice_wake",true);
            PowerManager power=(PowerManager)c.getSystemService(Context.POWER_SERVICE);power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK|PowerManager.ACQUIRE_CAUSES_WAKEUP,"mindpebble:voice-wake").acquire(2000);
            c.startActivity(open);((Vibrator)c.getSystemService(Context.VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(100,VibrationEffect.DEFAULT_AMPLITUDE));
            c.getSharedPreferences("wake-word",0).edit().putLong("last_trigger_at",System.currentTimeMillis()).apply();CaptureDiagnostics.event(c,"wake_triggered",PHRASE);
        }catch(RuntimeException e){CaptureDiagnostics.event(c,"wake_open_failed",e.getClass().getSimpleName());}
    }
    void reset(){queue.clear();reset=true;}
    void close(){running=false;ready=false;queue.clear();thread.interrupt();status("off");}
}
