package org.diy.liferecorder;

import android.media.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.json.*;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.*;

/** Offline WebRTC VAD, 500ms preroll / 800ms tail / 700ms merge. Originals survive. */
final class SpeechRetention {
    static void run(RecordStore store,String id,File source,ProviderClient.Cancel cancel)throws Exception{
        if(store.audio(id).optInt("source_retained",1)==0)throw new IOException("原音频已释放，不能重新筛选原音频");
        File pcm=File.createTempFile("vad-",".pcm",store.media),pending=File.createTempFile("vad-",".part",store.media);
        try{
            decode(source,pcm,cancel);long samples=pcm.length()/2;
            List<long[]> ranges=new ArrayList<>();
            try(RandomAccessFile input=new RandomAccessFile(pcm,"r");VadWebRTC vad=new VadWebRTC(SampleRate.SAMPLE_RATE_16K,FrameSize.FRAME_SIZE_320,Mode.NORMAL,0,0)){
                byte[] frame=new byte[640];long offset=0;int count;
                while((count=input.read(frame))!=-1){if(cancel.cancelled())throw new IOException("已取消");if(count<640)Arrays.fill(frame,count,640,(byte)0);
                    if(vad.isSpeech(frame))merge(ranges,Math.max(0,offset-8000),Math.min(samples,offset+count/2+12800));offset+=count/2;
                }
            }
            JSONObject metadata=new JSONObject(store.audio(id).getString("metadata"));JSONArray markers=metadata.optJSONArray("markers");
            if(markers!=null)for(int i=0;i<markers.length();i++){long at=markers.getJSONObject(i).getLong("offset_ms")*16;ranges.add(new long[]{Math.max(0,at-48000),Math.min(samples,at+128000)});}
            ranges.sort((a,b)->Long.compare(a[0],b[0]));List<long[]> merged=new ArrayList<>();for(long[] span:ranges)merge(merged,span[0],span[1]);ranges=merged;
            JSONArray mapping=new JSONArray();long outSamples=0;for(long[] span:ranges){mapping.put(new JSONObject().put("source_start_sample",span[0]).put("source_end_sample",span[1]).put("output_start_sample",outSamples).put("output_end_sample",outSamples+span[1]-span[0]));outSamples+=span[1]-span[0];}
            if(cancel.cancelled())throw new IOException("已取消");
            if(outSamples==0){store.update("audio",id,"vad_map",new JSONObject().put("version",1).put("sample_rate",16000).put("ranges",mapping).put("no_speech_detected",true).toString());return;}
            encode(pcm,pending,ranges,cancel);
            File dest=new File(store.media,id+"-speech.m4a");try(FileOutputStream out=new FileOutputStream(pending,true)){out.getFD().sync();}
            store.getWritableDatabase().beginTransaction();try {
            store.audio(id);if(cancel.cancelled())throw new IOException("已取消");if(!pending.renameTo(dest))throw new IOException("VAD文件保存失败");org.diy.lifecore.LocalSecurity.syncDirectory(store.media);
            store.update("audio",id,"vad_path",dest.getPath());store.update("audio",id,"vad_map",new JSONObject().put("version",1).put("sample_rate",16000).put("source_samples",samples).put("output_samples",outSamples).put("ranges",mapping).toString());store.getWritableDatabase().setTransactionSuccessful();}finally{store.getWritableDatabase().endTransaction();}
        }finally{pcm.delete();pending.delete();}
    }
    private static void merge(List<long[]> spans,long start,long end){if(end<=start)return;if(!spans.isEmpty()&&start<=spans.get(spans.size()-1)[1]+11200)spans.get(spans.size()-1)[1]=Math.max(end,spans.get(spans.size()-1)[1]);else spans.add(new long[]{start,end});}
    static void decode(File source,File dest,ProviderClient.Cancel cancel)throws Exception{
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;
        try(BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(dest))){
            extractor.setDataSource(source.getPath());MediaFormat format=null;for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);if(f.getString(MediaFormat.KEY_MIME).startsWith("audio/")){extractor.selectTrack(i);format=f;break;}}
            if(format==null)throw new IOException("找不到音轨");codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));codec.configure(format,null,null,0);codec.start();
            boolean inputDone=false,outputDone=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),encoding=AudioFormat.ENCODING_PCM_16BIT;
            double next=0;long frame=0;short previous=0;
            while(!outputDone){if(cancel.cancelled())throw new IOException("已取消");
                if(!inputDone){int idx=codec.dequeueInputBuffer(10000);if(idx>=0){ByteBuffer b=codec.getInputBuffer(idx);int n=extractor.readSampleData(b,0);if(n<0){codec.queueInputBuffer(idx,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{codec.queueInputBuffer(idx,0,n,extractor.getSampleTime(),0);extractor.advance();}}}
                int idx=codec.dequeueOutputBuffer(info,10000);
                if(idx==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=codec.getOutputFormat();rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);if(f.containsKey(MediaFormat.KEY_PCM_ENCODING))encoding=f.getInteger(MediaFormat.KEY_PCM_ENCODING);}
                else if(idx>=0){ByteBuffer b=codec.getOutputBuffer(idx).order(ByteOrder.LITTLE_ENDIAN);b.position(info.offset);b.limit(info.offset+info.size);int bytes=encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2;if(encoding!=AudioFormat.ENCODING_PCM_FLOAT&&encoding!=AudioFormat.ENCODING_PCM_16BIT)throw new IOException("PCM格式不支持");
                    while(b.remaining()>=channels*bytes){double sum=0;for(int c=0;c<channels;c++)sum+=bytes==4?Math.max(-32768,Math.min(32767,b.getFloat()*32767)):b.getShort();short sample=(short)(sum/channels);
                        if(frame==0){out.write(sample&255);out.write((sample>>8)&255);next=rate/16000.0;}else while(next<=frame){double f=next-(frame-1);short value=(short)Math.round(previous+(sample-previous)*f);out.write(value&255);out.write((value>>8)&255);next+=rate/16000.0;}
                        previous=sample;frame++;if(frame%16000==0&&dest.getParentFile().getUsableSpace()<64L*1024*1024)throw new IOException("剩余空间不足，原文件已保留");
                    }
                    outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(idx,false);
                }
            }
        }finally{extractor.release();if(codec!=null){try{codec.stop();}finally{codec.release();}}}
    }
    private static void encode(File pcm,File dest,List<long[]> ranges,ProviderClient.Cancel cancel)throws Exception{
        MediaFormat f=MediaFormat.createAudioFormat("audio/mp4a-latm",16000,1);f.setInteger(MediaFormat.KEY_BIT_RATE,32000);f.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        MediaCodec encoder=MediaCodec.createEncoderByType("audio/mp4a-latm");MediaMuxer muxer=new MediaMuxer(dest.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);boolean started=false;int track=-1;
        try(RandomAccessFile input=new RandomAccessFile(pcm,"r")){
            encoder.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start();MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int range=0;long position=ranges.get(0)[0],written=0;boolean eos=false,done=false;byte[] bytes=new byte[4096];
            while(!done){if(cancel.cancelled())throw new IOException("已取消");
                if(!eos){int idx=encoder.dequeueInputBuffer(10000);if(idx>=0){ByteBuffer b=encoder.getInputBuffer(idx);b.clear();int n=0;
                    while(range<ranges.size()&&n==0){long end=ranges.get(range)[1];if(position>=end){range++;if(range<ranges.size())position=ranges.get(range)[0];continue;}int length=(int)Math.min(Math.min(b.remaining(),bytes.length),(end-position)*2);length-=length%2;input.seek(position*2);input.readFully(bytes,0,length);b.put(bytes,0,length);n=length;position+=length/2;}
                    if(range>=ranges.size()&&n==0){encoder.queueInputBuffer(idx,0,0,written*1000000/16000,MediaCodec.BUFFER_FLAG_END_OF_STREAM);eos=true;}else{encoder.queueInputBuffer(idx,0,n,written*1000000/16000,0);written+=n/2;}
                }}
                int idx=encoder.dequeueOutputBuffer(info,10000);if(idx==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){track=muxer.addTrack(encoder.getOutputFormat());muxer.start();started=true;}else if(idx>=0){if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0&&info.size>0){if(!started)throw new IOException("编码器未初始化");ByteBuffer b=encoder.getOutputBuffer(idx);b.position(info.offset);b.limit(info.offset+info.size);muxer.writeSampleData(track,b,info);}done=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;encoder.releaseOutputBuffer(idx,false);}
            }
        }finally{try{encoder.stop();}finally{encoder.release();}try{if(started)muxer.stop();}finally{muxer.release();}}
    }
}
