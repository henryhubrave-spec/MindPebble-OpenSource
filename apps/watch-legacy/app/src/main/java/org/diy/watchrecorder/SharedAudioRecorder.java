package org.diy.watchrecorder;

import android.media.*;
import android.os.Process;
import java.io.*;
import java.nio.ByteBuffer;

/** One microphone, shared PCM for AAC and KWS. Encoding always has priority over detection. */
final class SharedAudioRecorder {
    interface Samples { void accept(short[] pcm,int length); }
    private final String path;private final Samples samples;private final java.util.function.Consumer<Exception> error;
    private AudioRecord audio;private MediaCodec encoder;private MediaMuxer muxer;private Thread reader;
    private volatile boolean running;private volatile Exception failure;private volatile int amplitude;
    private boolean muxStarted;private volatile boolean finalized;private int track=-1;private long frames;
    SharedAudioRecorder(String path,Samples samples,java.util.function.Consumer<Exception> error){this.path=path;this.samples=samples;this.error=error;}
    void start(java.util.function.BooleanSupplier allowed)throws Exception{
        try{
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IOException("不支持16kHz单声道采集");
            audio=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,16000));
            if(audio.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("麦克风初始化失败");
            MediaFormat format=MediaFormat.createAudioFormat("audio/mp4a-latm",16000,1);format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);format.setInteger(MediaFormat.KEY_BIT_RATE,32000);format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4096);
            encoder=MediaCodec.createEncoderByType("audio/mp4a-latm");encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start();muxer=new MediaMuxer(path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if(!allowed.getAsBoolean())throw new IOException("记录意图已改变");audio.startRecording();if(audio.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("麦克风被占用");
            running=true;reader=new Thread(this::read,"shared-microphone");reader.start();
        }catch(Exception e){closeNative();throw e;}
    }
    private void read(){
        short[] pcm=new short[640];
        try{
            try{Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);}catch(SecurityException ignored){}
            while(running){int n=audio.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);if(!running)break;if(n<=0)throw new IOException("麦克风读取失败 "+n);
                int peak=0;for(int i=0;i<n;i++)peak=Math.max(peak,Math.abs((int)pcm[i]));amplitude=peak;
                queue(pcm,n,false);if(samples!=null)samples.accept(pcm,n);
            }
            boolean ended=queue(pcm,0,true);long until=android.os.SystemClock.elapsedRealtime()+2000;
            while(!ended&&android.os.SystemClock.elapsedRealtime()<until)ended=drain(10000);
            if(!ended)throw new IOException("AAC编码结束超时");
        }catch(Exception e){failure=e;if(running)error.accept(e);}finally{running=false;closeNative();}
    }
    private boolean queue(short[] pcm,int n,boolean eos)throws Exception{
        long until=android.os.SystemClock.elapsedRealtime()+2000;
        while(true){int index=encoder.dequeueInputBuffer(10000);if(index>=0){ByteBuffer b=encoder.getInputBuffer(index);b.clear();if(b.remaining()<n*2)throw new IOException("AAC输入缓冲区不足");for(int i=0;i<n;i++){b.put((byte)pcm[i]);b.put((byte)(pcm[i]>>8));}encoder.queueInputBuffer(index,0,n*2,frames*1000000L/16000,eos?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);frames+=n;return drain(0);}drain(0);if(android.os.SystemClock.elapsedRealtime()>until)throw new IOException("AAC编码器无响应");}
    }
    private boolean drain(long wait)throws Exception{
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        while(true){int index=encoder.dequeueOutputBuffer(info,wait);wait=0;
            if(index==MediaCodec.INFO_TRY_AGAIN_LATER)return false;
            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){if(muxStarted)throw new IOException("AAC格式重复变更");track=muxer.addTrack(encoder.getOutputFormat());muxer.start();muxStarted=true;}
            else if(index>=0){boolean end=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                try{if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){if(!muxStarted)throw new IOException("AAC封装尚未初始化");ByteBuffer b=encoder.getOutputBuffer(index);b.position(info.offset);b.limit(info.offset+info.size);muxer.writeSampleData(track,b,info);}}finally{encoder.releaseOutputBuffer(index,false);}if(end)return true;
            }
        }
    }
    void stop()throws Exception{
        running=false;if(audio!=null)try{audio.stop();}catch(RuntimeException ignored){}
        if(reader!=null){reader.join(4500);if(reader.isAlive())throw new IOException("采集线程结束超时");}
        else closeNative();if(failure!=null&&!finalized)throw failure;
    }
    void release(){try{stop();}catch(Exception ignored){}}
    int getMaxAmplitude()throws IOException{if(failure!=null)throw new IOException("采集已中断",failure);return amplitude;}
    private void closeNative(){
        if(audio!=null){try{audio.release();}catch(RuntimeException ignored){}audio=null;}
        if(encoder!=null){try{encoder.stop();}catch(RuntimeException ignored){}try{encoder.release();}catch(RuntimeException ignored){}encoder=null;}
        if(muxer!=null){try{if(muxStarted){muxer.stop();finalized=frames>0;}}catch(RuntimeException e){if(failure==null)failure=e;}try{muxer.release();}catch(RuntimeException ignored){}muxer=null;}
    }
}
