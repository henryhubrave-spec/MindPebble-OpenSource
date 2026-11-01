package org.diy.liferecorder;

import android.os.*;
import com.k2fsa.sherpa.onnx.*;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.*;
import java.io.*;
import java.util.*;
import org.json.*;
import org.diy.lifecore.LocalSecurity;

/** CPU-only offline speech. Bounded utterances, original-time offsets, no provider dependency. */
final class OfflineSpeech {
    private static final int RATE=16000,MAX=12*RATE;
    private static void check(ProviderClient.Cancel cancel)throws IOException{if(cancel.cancelled())throw new IOException("本地处理已暂停");}
    private static final class Engine implements AutoCloseable {
        OfflineRecognizer offline;OnlineRecognizer online;SpeakerEmbeddingExtractor speaker;
        Engine(OfflinePacks packs,String id)throws Exception{this(packs,id,"auto");}
        Engine(OfflinePacks packs,String id,String language)throws Exception{
            String model=packs.file(id,"model.int8.onnx").getPath();
            if(id.equals("standard")){
                OfflineRecognizerConfig c=new OfflineRecognizerConfig();OfflineModelConfig m=c.getModelConfig();m.getSenseVoice().setModel(model);m.getSenseVoice().setLanguage(language);m.getSenseVoice().setUseInverseTextNormalization(true);m.setTokens(packs.file(id,"tokens.txt").getPath());m.setNumThreads(1);m.setProvider("cpu");offline=new OfflineRecognizer(null,c);
            }else if(id.equals("lite")){
                OnlineRecognizerConfig c=new OnlineRecognizerConfig();OnlineModelConfig m=c.getModelConfig();m.getZipformer2Ctc().setModel(model);m.setTokens(packs.file(id,"tokens.txt").getPath());m.setNumThreads(1);m.setProvider("cpu");m.setModelingUnit("cjkchar");c.setEnableEndpoint(false);online=new OnlineRecognizer(null,c);
            }else if(id.equals("speaker"))speaker=new SpeakerEmbeddingExtractor(null,new SpeakerEmbeddingExtractorConfig(packs.file(id,"campplus.onnx").getPath(),1,false,"cpu"));
            else throw new IOException("不支持的离线包");
        }
        String text(float[] samples,ProviderClient.Cancel cancel)throws Exception{
            check(cancel);String text;
            if(offline!=null){OfflineStream stream=offline.createStream();try{stream.acceptWaveform(samples,RATE);offline.decode(stream);text=offline.getResult(stream).getText();}finally{stream.release();}}
            else if(online!=null){OnlineStream stream=online.createStream("");try{stream.acceptWaveform(samples,RATE);stream.acceptWaveform(new float[RATE/2],RATE);stream.inputFinished();while(online.isReady(stream)){check(cancel);online.decode(stream);}text=online.getResult(stream).getText();}finally{stream.release();}}
            else throw new IOException("资源包不支持转写");check(cancel);return text.replaceAll("<\\|[^|]*\\|>","").trim();
        }
        float[] embedding(float[] samples)throws Exception{OnlineStream stream=speaker.createStream();try{stream.acceptWaveform(samples,RATE);stream.inputFinished();if(!speaker.isReady(stream))throw new IOException("有效语音不足，请使用更长、更清晰的样本");float[] value=speaker.compute(stream);double norm=0;for(float x:value){if(!Float.isFinite(x))throw new IOException("声纹无效");norm+=x*x;}if(norm<1e-12)throw new IOException("声纹无效");return value;}finally{stream.release();}}
        public void close(){if(offline!=null)offline.release();if(online!=null)online.release();if(speaker!=null)speaker.release();}
    }
    static JSONObject benchmark(OfflinePacks packs,String id,ProviderClient.Cancel cancel)throws Exception{
        synchronized(OfflinePacks.ENGINE_LOCK){packs.verify(id,packs.directory(id));check(cancel);File pcm=File.createTempFile("benchmark-",".pcm",packs.context.getCacheDir());
            try{SpeechRetention.decode(packs.file(id,"benchmark.wav"),pcm,cancel);float[] samples=read(pcm,0,(int)Math.min(pcm.length()/2,30L*RATE));
                long before=SystemClock.elapsedRealtime();try(Engine engine=new Engine(packs,id)){long loaded=SystemClock.elapsedRealtime();String text="";if(id.equals("speaker")){float[] e=engine.embedding(samples);if(e.length<16)throw new IOException("声纹模型输出无效");}else{text=engine.text(samples,cancel);if(text.isEmpty())throw new IOException("公开测试音频未产生文字，不能启用该包");}
                    long finish=SystemClock.elapsedRealtime();check(cancel);return new JSONObject().put("load_ms",loaded-before).put("inference_ms",finish-loaded).put("audio_ms",samples.length*1000L/RATE).put("rtf",(finish-loaded)/(samples.length*1000.0/RATE)).put("pss_kb",Debug.getPss()).put("text",text).put("device",Build.MANUFACTURER+" "+Build.MODEL).put("at",System.currentTimeMillis());
                }
            }finally{pcm.delete();}
        }
    }
    interface Progress {void update(String stage,long done,long total,String unit);}
    static JSONObject transcribe(OfflinePacks packs,String id,File source,ProviderClient.Cancel cancel)throws Exception{return transcribe(packs,id,source,cancel,(stage,done,total,unit)->{});}
    static JSONObject transcribe(OfflinePacks packs,String id,File source,ProviderClient.Cancel cancel,Progress progress)throws Exception{return transcribe(packs,id,source,cancel,progress,AppLanguage.code(packs.context));}
    static JSONObject transcribe(OfflinePacks packs,String id,File source,ProviderClient.Cancel cancel,Progress progress,String language)throws Exception{
        synchronized(OfflinePacks.ENGINE_LOCK){
            if(!packs.ready(id))throw new IOException("离线包未通过运行验证，请到离线能力下载或验证");progress.update("校验语音资源",0,0,"");packs.verify(id,packs.directory(id));
            File pcm=File.createTempFile("local-asr-",".pcm",packs.context.getCacheDir());
            try{progress.update("解码音频",0,0,"");SpeechRetention.decode(source,pcm,cancel);progress.update("检测有效语音",0,0,"");List<long[]> spans=recognitionSpans(spans(pcm,cancel),pcm.length()/2);JSONArray segments=new JSONArray();StringBuilder transcript=new StringBuilder();long before=SystemClock.elapsedRealtime();
                boolean voice=packs.ready("speaker");SpeakerMatcher.Profile mine=null;long voicedMs=0;String voiceWarning="";try{mine=readVoice(packs);}catch(Exception e){voiceWarning="本人声纹无法读取，请重新录入；转写不受影响";}List<SpeakerMatcher.Observation> speakers=new ArrayList<>();
                progress.update("加载转写模型",0,0,"");try(Engine asr=new Engine(packs,id,language);Engine speaker=voice?new Engine(packs,"speaker"):null){
                    int completed=0;
                    for(long[] span:spans){progress.update("转写第 "+(completed+1)+" 段",completed,spans.size(),"语音段");check(cancel);float[] samples=read(pcm,span[0],(int)(span[1]-span[0]));String text=asr.text(samples,cancel);float[] cleanVoice=voiced(samples,cancel);voicedMs+=cleanVoice.length*1000L/RATE;completed++;if(text.isEmpty()){progress.update("转写中",completed,spans.size(),"语音段");continue;}
                        JSONObject segment=new JSONObject().put("start_ms",span[0]*1000/RATE).put("end_ms",span[1]*1000/RATE).put("text",text).put("raw_text",text).put("speaker","身份不确定").put("speaker_confirmed",false);
                        segment.put("speaker_speech_ms",cleanVoice.length*1000L/RATE);if(speaker!=null&&TranscriptDocument.visible(segment)){try{List<float[]> views=embeddings(speaker,cleanVoice,cancel);if(!views.isEmpty()&&coherent(views))speakers.add(new SpeakerMatcher.Observation(segments.length(),average(views),cleanVoice.length*1000L/RATE));else segment.put("speaker_quality","insufficient-speech");}catch(IOException insufficient){segment.put("speaker_quality","insufficient-speech");}}
                        if(android.os.Build.VERSION.SDK_INT>=29&&((PowerManager)packs.context.getSystemService(android.content.Context.POWER_SERVICE)).getCurrentThermalStatus()>=PowerManager.THERMAL_STATUS_SEVERE)Thread.sleep(150);progress.update("已处理语音段",completed,spans.size(),"语音段");segments.put(segment);if(transcript.length()>0)transcript.append('\n');transcript.append(text);
                    }
                }
                check(cancel);progress.update("核对说话人分组",0,0,"");SpeakerMatcher.assign(segments,speakers,mine);VoiceProfiles.assign(packs,segments,speakers);JSONArray readable=new JSONArray();for(int n=0;n<segments.length();n++)if(TranscriptDocument.visible(segments.getJSONObject(n)))readable.put(segments.getJSONObject(n));String rawText=transcript.toString();transcript.setLength(0);for(int n=0;n<readable.length();n++){if(n>0)transcript.append('\n');transcript.append(TranscriptDocument.clean(readable.getJSONObject(n).optString("text")));}return new JSONObject().put("asr_context_window_ms",30000).put("timestamp_precision","ASR context window, not sentence alignment").put("raw_text",rawText).put("filtered_segments",segments.length()-readable.length()).put("version",2).put("voiced_ms",voicedMs).put("language",language).put("engine",id).put("model_version",packs.pack(id).getString("version")).put("text",transcript.toString()).put("segments",segments).put("audio_ms",pcm.length()*1000/(RATE*2)).put("inference_ms",SystemClock.elapsedRealtime()-before).put("no_speech_detected",spans.isEmpty()).put("speaker_scope","30-second ASR context windows; speaker matching is a candidate for the whole window, overlapping speakers are not separated").put("speaker_algorithm","duration-weighted-centroid-v2").put("voice_threshold",SpeakerMatcher.MATCH).put("voice_warning",voiceWarning);
            }finally{pcm.delete();}
        }
    }
    static void mapToOriginal(JSONObject result,JSONObject audio)throws Exception{
        if(audio.optInt("source_retained",1)!=0)return;
        JSONArray ranges=new JSONObject(audio.getString("vad_map")).getJSONArray("ranges"),segments=result.getJSONArray("segments");
        for(int i=0;i<segments.length();i++){JSONObject segment=segments.getJSONObject(i);long start=segment.getLong("start_ms"),end=segment.getLong("end_ms");segment.put("asset_start_ms",start).put("asset_end_ms",end).put("start_ms",originalTime(start,ranges,false)).put("end_ms",originalTime(end,ranges,true));}
    }
    private static long originalTime(long at,JSONArray ranges,boolean end)throws Exception{
        long sample=at*16;for(int i=0;i<ranges.length();i++){JSONObject r=ranges.getJSONObject(i);if(sample<r.getLong("output_end_sample")||(end&&sample==r.getLong("output_end_sample")))return (r.getLong("source_start_sample")+Math.max(0,sample-r.getLong("output_start_sample")))/16;}
        return ranges.length()==0?at:ranges.getJSONObject(ranges.length()-1).getLong("source_end_sample")/16;
    }
    static void enroll(OfflinePacks packs,File sample)throws Exception{
        synchronized(OfflinePacks.ENGINE_LOCK){if(!packs.ready("speaker"))throw new IOException("请先准备声纹识别包");File pcm=File.createTempFile("enrollment-",".pcm",packs.context.getCacheDir());try{SpeechRetention.decode(sample,pcm,()->false);long count=pcm.length()/2;if(count<5*RATE||count>31*RATE)throw new IOException("请选择 5–30 秒本人语音");float[] clean=voiced(read(pcm,0,(int)count),()->false);if(clean.length<3*RATE)throw new IOException("有效语音不足，请选择清晰说话的样本");saveVoice(packs,clean,true);}finally{pcm.delete();}}
    }
    static String supplement(OfflinePacks packs,JSONObject audio,JSONObject segment)throws Exception{return supplement(packs,audio,segment,VoiceProfiles.SELF);}
    static String supplement(OfflinePacks packs,JSONObject audio,JSONObject segment,String profileId)throws Exception{
        synchronized(OfflinePacks.ENGINE_LOCK){if(!packs.ready("speaker"))throw new IOException("请先准备声纹识别包");long start=segment.optLong("start_ms"),end=segment.optLong("end_ms");if(audio.optInt("source_retained",1)==0){if(!segment.has("asset_start_ms")||!segment.has("asset_end_ms"))throw new IOException("请重新转写以恢复精简音频时间映射");start=segment.getLong("asset_start_ms");end=segment.getLong("asset_end_ms");}if(start<0||end<=start||end-start>40000)throw new IOException("请选择一段 3–40 秒清晰的单人语音");File pcm=File.createTempFile("enrollment-",".pcm",packs.context.getCacheDir());try{SpeechRetention.decode(new File(audio.getString("path")),pcm,()->false);long from=start*16,to=Math.min(pcm.length()/2,end*16);if(to<=from)throw new IOException("该片段音频不可用");float[] clean=voiced(read(pcm,from,(int)(to-from)),()->false);if(clean.length<2400L*RATE/1000)throw new IOException("有效语音过短，请选择更长的单人语音");return VoiceProfiles.add(packs,profileId,voiceProfile(packs,clean),audio,segment,false);}finally{pcm.delete();}}
    }
    private static JSONObject voiceProfile(OfflinePacks packs,float[] samples)throws Exception{try(Engine speaker=new Engine(packs,"speaker")){List<float[]> templates=embeddings(speaker,samples,()->false);if(templates.isEmpty())throw new IOException("有效语音不足，请选择清晰说话的样本");if(!coherent(templates))throw new IOException("样本声音差异较大，请只选择一人清晰说话的片段");return SpeakerMatcher.profile(templates,null,false,packs.pack("speaker").getString("version"));}}
    private static void saveVoice(OfflinePacks packs,float[] samples,boolean append)throws Exception{VoiceProfiles.add(packs,VoiceProfiles.SELF,voiceProfile(packs,samples),null,null,!append);}
    private static SpeakerMatcher.Profile readVoice(OfflinePacks packs)throws Exception{String stored=packs.prefs.getString("my-voice","");if(stored.isEmpty())return null;JSONObject record=new JSONObject(LocalSecurity.open(stored));if(!record.getString("model_version").equals(packs.pack("speaker").getString("version")))throw new IOException("声纹资源已更新，请重新录入本人声音");return new SpeakerMatcher.Profile(record);}
    private static float[] average(List<float[]> values){float[] result=new float[values.get(0).length];for(float[] value:values)for(int i=0;i<result.length;i++)result[i]+=value[i];return SpeakerMatcher.normalize(result);}
    private static List<float[]> embeddings(Engine speaker,float[] speech,ProviderClient.Cancel cancel)throws Exception{ArrayList<float[]> values=new ArrayList<>();if(speech.length<2400L*RATE/1000)return values;double sum=0;int clipped=0;for(float x:speech){sum+=x*x;if(Math.abs(x)>.99)clipped++;}if(Math.sqrt(sum/speech.length)<.001||clipped>speech.length*.15)return values;int window=Math.min(4*RATE,speech.length),step=3*RATE;for(int at=0;;at+=step){check(cancel);int start=Math.min(at,speech.length-window);float[] part=Arrays.copyOfRange(speech,start,start+window);double mean=0;for(float x:part)mean+=x;mean/=part.length;for(int i=0;i<part.length;i++)part[i]-=mean;values.add(SpeakerMatcher.normalize(speaker.embedding(part)));if(start+window>=speech.length)break;}return values;}
    private static float[] voiced(float[] samples,ProviderClient.Cancel cancel)throws Exception{float[] out=new float[samples.length];int count=0;try(VadWebRTC vad=new VadWebRTC(SampleRate.SAMPLE_RATE_16K,FrameSize.FRAME_SIZE_320,Mode.NORMAL,0,0)){byte[] bytes=new byte[640];for(int at=0;at<samples.length;at+=320){check(cancel);Arrays.fill(bytes,(byte)0);int length=Math.min(320,samples.length-at);for(int n=0;n<length;n++){short value=(short)Math.max(-32768,Math.min(32767,Math.round(samples[at+n]*32768)));bytes[n*2]=(byte)value;bytes[n*2+1]=(byte)(value>>8);}if(vad.isSpeech(bytes)){System.arraycopy(samples,at,out,count,length);count+=length;}}}return Arrays.copyOf(out,count);}
    static JSONObject rematch(OfflinePacks packs,String pack,File source,JSONObject previous,ProviderClient.Cancel cancel,Progress progress)throws Exception{
        synchronized(OfflinePacks.ENGINE_LOCK){if(!packs.ready("speaker"))throw new IOException("请先准备声纹识别包");packs.verify("speaker",packs.directory("speaker"));JSONObject result=new JSONObject(previous.toString());JSONArray segments=result.getJSONArray("segments");File pcm=File.createTempFile("speaker-rematch-",".pcm",packs.context.getCacheDir());try{progress.update("解码音频",0,0,"");SpeechRetention.decode(source,pcm,cancel);List<SpeakerMatcher.Observation> observations=new ArrayList<>();try(Engine speaker=new Engine(packs,"speaker")){for(int i=0;i<segments.length();i++){check(cancel);JSONObject segment=segments.getJSONObject(i);if(segment.optBoolean("speaker_confirmed"))continue;segment.put("speaker","身份不确定").put("speaker_confidence","insufficient-evidence");segment.remove("voice_similarity");segment.remove("speaker_group_similarity");segment.remove("speaker_method");long start=segment.optLong("asset_start_ms",segment.optLong("start_ms"))*16,end=Math.min(pcm.length()/2,segment.optLong("asset_end_ms",segment.optLong("end_ms"))*16);if(start<0||end<=start||end-start>40L*RATE)continue;float[] clean=voiced(read(pcm,start,(int)(end-start)),cancel);try{List<float[]> embeddings=embeddings(speaker,clean,cancel);if(!embeddings.isEmpty()&&coherent(embeddings))observations.add(new SpeakerMatcher.Observation(i,average(embeddings),clean.length*1000L/RATE));}catch(IOException insufficient){check(cancel);segment.put("speaker_quality","insufficient-speech");}progress.update("重新核对本人声纹",i+1,segments.length(),"语音段");}}check(cancel);SpeakerMatcher.assign(segments,observations,readVoice(packs));VoiceProfiles.assign(packs,segments,observations);return result.put("speaker_algorithm","duration-weighted-centroid-v2").put("voice_threshold",SpeakerMatcher.MATCH).put("speaker_rematched",true);}finally{pcm.delete();}}
    }
    private static boolean coherent(List<float[]> views){for(int a=0;a<views.size();a++)for(int b=a+1;b<views.size();b++)if(cosine(views.get(a),views.get(b))<.45)return false;return true;}
    static double cosine(float[] a,float[] b){if(a.length!=b.length)return -1;double dot=0,aa=0,bb=0;for(int i=0;i<a.length;i++){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}return aa==0||bb==0?-1:dot/Math.sqrt(aa*bb);}
    private static float[] read(File pcm,long start,int count)throws IOException{float[] out=new float[count];byte[] bytes=new byte[count*2];try(RandomAccessFile in=new RandomAccessFile(pcm,"r")){in.seek(start*2);in.readFully(bytes);}for(int i=0;i<count;i++)out[i]=(short)((bytes[i*2]&255)|((bytes[i*2+1]&255)<<8))/32768f;return out;}
    /** VAD gates whole context windows; it never schedules one inference per tiny VAD island. */
    static List<long[]> recognitionSpans(List<long[]> speech,long samples){
        List<long[]> result=new ArrayList<>();long window=30L*RATE;
        for(long start=0;start<samples;start+=window){long end=Math.min(samples,start+window);boolean voiced=false;for(long[] span:speech)if(span[0]<end&&span[1]>start){voiced=true;break;}if(!voiced)continue;
            if(end-start<10L*RATE&&!result.isEmpty()&&result.get(result.size()-1)[1]==start)result.get(result.size()-1)[1]=end;else result.add(new long[]{start,end});}
        return result;
    }
    static List<long[]> spans(File pcm,ProviderClient.Cancel cancel)throws Exception{
        List<long[]> result=new ArrayList<>();long samples=pcm.length()/2,start=-1,last=-1,position=0,floor=0;
        try(RandomAccessFile in=new RandomAccessFile(pcm,"r");VadWebRTC vad=new VadWebRTC(SampleRate.SAMPLE_RATE_16K,FrameSize.FRAME_SIZE_320,Mode.NORMAL,0,0)){
            byte[] bytes=new byte[640];int count;while((count=in.read(bytes))!=-1){check(cancel);if(count<640)Arrays.fill(bytes,count,640,(byte)0);if(vad.isSpeech(bytes)){if(start<0)start=Math.max(floor,position-8000);last=position+count/2;}
                position+=count/2;if(start>=0&&(position-start>=MAX||position-last>=12800)){long end=Math.min(samples,Math.min(position,last+12800));if(end>start)result.add(new long[]{start,end});floor=end;start=-1;last=-1;}
            }if(start>=0)result.add(new long[]{start,Math.min(samples,last+12800)});
        }return result;
    }
}
