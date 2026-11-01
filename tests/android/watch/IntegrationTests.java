package org.diy.watchrecorder;
import android.app.*;import android.os.*;import java.io.*;import java.nio.*;import org.json.*;import org.diy.lifecore.LocalSecurity;
public final class IntegrationTests extends Instrumentation {
 private Bundle args;public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
 public void onStart(){Bundle result=new Bundle();int checks=0;try{
    if(!android.os.Build.HARDWARE.equals("ranchu"))throw new IOException("Emulator only");android.content.Context c=getTargetContext();String target=args.getString("address");
    if(args.getString("phase","").equals("power045")){checks+=PowerTests.run(this,c);result.putInt("checks",checks);finish(-1,result);return;}
    if(args.getString("phase","").equals("archive042")){
      File dir=new File(c.getFilesDir(),"recordings");dir.mkdirs();String name="archive042.m4a";File file=new File(dir,name);try(FileOutputStream out=new FileOutputStream(file)){out.write(wave());}String sha=LocalSecurity.hash(file);File side=new File(file.getPath()+".json");try(FileOutputStream out=new FileOutputStream(side)){out.write(new JSONObject().put("started_at_epoch_ms",System.currentTimeMillis()-8L*86400000).toString().getBytes("UTF-8"));}android.content.SharedPreferences receipts=c.getSharedPreferences("phone-receipts",0),processed=c.getSharedPreferences("archive-processed",0);processed.edit().clear().commit();receipts.edit().putString(name,sha).putString(name+".state","DURABLE").commit();ArchivePolicy.prune(c);if(!file.exists())throw new IOException("unprocessed original removed");checks++;processed.edit().putString(name,sha).commit();receipts.edit().remove(name).commit();ArchivePolicy.prune(c);if(!file.exists())throw new IOException("unreceived original removed");checks++;receipts.edit().putString(name,sha).commit();ArchivePolicy.prune(c);if(file.exists()||side.exists())throw new IOException("processed 8-day original not cleaned");checks++;
      try(FileOutputStream out=new FileOutputStream(file)){out.write(wave());}try(FileOutputStream out=new FileOutputStream(side)){out.write(new JSONObject().put("started_at_epoch_ms",System.currentTimeMillis()).toString().getBytes("UTF-8"));}JSONObject cmd=new JSONObject().put("id","test").put("name",name).put("sha","wrong");JSONArray ack=ArchivePolicy.apply(c,new JSONObject().put("commands",new JSONArray().put(cmd)));if(!file.exists()||!ack.getJSONObject(0).getString("status").equals("mismatch"))throw new IOException("hash mismatch removed file");checks++;cmd.put("sha",sha);ack=ArchivePolicy.apply(c,new JSONObject().put("commands",new JSONArray().put(cmd)));if(file.exists()||!ack.getJSONObject(0).getString("status").equals("deleted"))throw new IOException("explicit hash-bound removal failed");checks++;ack=ArchivePolicy.apply(c,new JSONObject().put("commands",new JSONArray().put(cmd)));if(!ack.getJSONObject(0).getString("status").equals("absent"))throw new IOException("repeat removal not idempotent");checks++;result.putInt("checks",checks);finish(-1,result);return;
    }
    if(args.getString("phase","").equals("stitch")){
      android.content.SharedPreferences prefs=c.getSharedPreferences("context-reminder",0);prefs.edit().clear().commit();WatchContextReminder guard=new WatchContextReminder();JSONObject policy=new JSONObject().put("context_reminder",new JSONObject().put("enabled",true).put("hour",0).put("minute",0).put("heart",70).put("lux",50));long now=System.currentTimeMillis();guard.observe(c,policy,new JSONObject().put("type","heart_rate").put("value",64).put("observed_at_epoch_ms",now));if(prefs.contains("last-day"))throw new IOException("missing light must not trigger");checks++;
      guard.observe(c,policy,new JSONObject().put("type","light").put("value",28).put("observed_at_epoch_ms",now-900001));if(prefs.contains("last-day"))throw new IOException("stale light must not trigger");checks++;
      guard.observe(c,policy,new JSONObject().put("type","light").put("value",28).put("observed_at_epoch_ms",now));if(!prefs.contains("last-day"))throw new IOException("fresh match must trigger");checks++;
      String sent=prefs.getString("last-day","");guard.observe(c,policy,new JSONObject().put("type","heart_rate").put("value",64).put("observed_at_epoch_ms",now));if(!sent.equals(prefs.getString("last-day","")))throw new IOException("once per day guard missing");checks++;
      prefs.edit().clear().commit();policy.getJSONObject("context_reminder").put("enabled",false);guard.observe(c,policy,new JSONObject().put("type","light").put("value",28).put("observed_at_epoch_ms",now));if(prefs.contains("last-day"))throw new IOException("disabled rule triggered");checks++;result.putInt("checks",checks);finish(-1,result);return;
    }
    if(args.getString("phase","").equals("resume")||args.getString("phase","").equals("resume-shared")){
      c.getSharedPreferences("wake-word",0).edit().putBoolean("enabled",args.getString("phase","").equals("resume-shared")).commit();
      startActivitySync(new android.content.Intent(c,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
      File dir=new File(c.getFilesDir(),"recordings");dir.mkdirs();java.util.Set<String> before=new java.util.HashSet<>();for(File f:dir.listFiles())before.add(f.getName());
      android.content.SharedPreferences prefs=c.getSharedPreferences("recorder",0);
      c.startForegroundService(new android.content.Intent(c,RecordingService.class).setAction(RecordingService.START));Thread.sleep(2200);
      if(!prefs.getString("state","").equals("recording"))throw new IOException("capture not running");checks++;
      long oldEpoch=CaptureIntent.epoch(c);RecordingService svc=service();android.os.Handler worker=(android.os.Handler)field(svc,"worker");
      // A real MediaRecorder state error, injected only by emulator instrumentation.
      java.util.concurrent.CountDownLatch fault=new java.util.concurrent.CountDownLatch(1);
      worker.post(()->{try{Object shared=field(svc,"sharedRecorder");if(shared!=null)((android.media.AudioRecord)field(shared,"audio")).stop();else{((android.media.MediaRecorder)field(svc,"recorder")).reset();((Runnable)field(svc,"rotate")).run();}}catch(Exception e){throw new RuntimeException(e);}finally{fault.countDown();}});
      if(!fault.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("fault timeout");Thread.sleep(200);
      if(!prefs.getString("state","").equals("recovering")||!CaptureIntent.mode(c).equals(CaptureIntent.RUNNING))throw new IOException("error did not retain enabled intent");checks++;
      Thread.sleep(4300);if(!prefs.getString("state","").equals("recording"))throw new IOException("media error did not resume");checks++;
      c.startService(new android.content.Intent(c,RecordingService.class).setAction(RecordingService.PAUSE));Thread.sleep(700);
      new CaptureResumeReceiver().onReceive(c,new android.content.Intent(CaptureIntent.RECOVER).putExtra("capture_epoch",oldEpoch));Thread.sleep(3500);
      if(!prefs.getString("state","").equals("paused")||!CaptureIntent.mode(c).equals(CaptureIntent.PAUSED))throw new IOException("stale alarm undid pause");checks++;
      if(((android.app.job.JobScheduler)c.getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE)).getPendingJob(CaptureIntent.JOB_ID)!=null)throw new IOException("paused watchdog not canceled");checks++;
      c.startForegroundService(new android.content.Intent(c,RecordingService.class).setAction(RecordingService.START));Thread.sleep(1700);
      final RecordingService active=service();final int[] sticky={0};runOnMainSync(()->sticky[0]=active.onStartCommand(null,0,1000));Thread.sleep(500);
      if(sticky[0]!=android.app.Service.START_STICKY||!prefs.getString("state","").equals("recording"))throw new IOException("null sticky intent stopped capture");checks++;
      c.startService(new android.content.Intent(c,RecordingService.class).setAction(RecordingService.STOP));Thread.sleep(700);
      new CaptureResumeReceiver().onReceive(c,new android.content.Intent(CaptureIntent.RECOVER).putExtra("capture_epoch",CaptureIntent.epoch(c)-1));Thread.sleep(3500);
      if(RecordingService.alive||!CaptureIntent.mode(c).equals(CaptureIntent.OFF)||!prefs.getString("state","").equals("idle"))throw new IOException("stale alarm undid stop");checks++;
      String log=new String(java.nio.file.Files.readAllBytes(new File(c.getFilesDir(),"diagnostics/capture.jsonl").toPath()),"UTF-8");if(!log.contains("capture_interrupted")||!log.contains("retry_scheduled"))throw new IOException("diagnostics missing");checks++;
      for(File f:dir.listFiles())if(!before.contains(f.getName()))f.delete();result.putInt("checks",checks);finish(-1,result);return;
    }
    if(args.getString("phase","").equals("wake-model")){
      com.k2fsa.sherpa.onnx.KeywordSpotterConfig cfg=new com.k2fsa.sherpa.onnx.KeywordSpotterConfig();com.k2fsa.sherpa.onnx.OnlineModelConfig model=cfg.getModelConfig();String base="wakeword/";model.getTransducer().setEncoder(base+"encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx");model.getTransducer().setDecoder(base+"decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx");model.getTransducer().setJoiner(base+"joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx");model.setTokens(base+"tokens.txt");model.setNumThreads(1);model.setProvider("cpu");cfg.setKeywordsFile("wakeword/keywords.txt");cfg.setKeywordsScore(1.5f);cfg.setKeywordsThreshold(.5f);cfg.setNumTrailingBlanks(2);
      com.k2fsa.sherpa.onnx.KeywordSpotter kws=new com.k2fsa.sherpa.onnx.KeywordSpotter(c.getAssets(),cfg);
      try{
        for(String name:new String[]{"positive","negative","silence"}){
          com.k2fsa.sherpa.onnx.OnlineStream stream=kws.createStream("");try{float[] samples=name.equals("silence")?new float[48000]:pcm(new File(args.getString(name)));boolean hit=false;long start=SystemClock.elapsedRealtime();
          for(int offset=0;offset<samples.length+16000;offset+=640){float[] chunk=new float[640];if(offset<samples.length)System.arraycopy(samples,offset,chunk,0,Math.min(640,samples.length-offset));stream.acceptWaveform(chunk,16000);while(kws.isReady(stream)){kws.decode(stream);if(!kws.getResult(stream).getKeyword().isEmpty()){hit=true;kws.reset(stream);}}}
          if(hit!=name.equals("positive"))throw new IOException("Unexpected wake result: "+name+" hit="+hit);checks++;result.putLong(name+"_elapsed_ms",SystemClock.elapsedRealtime()-start);
          }finally{stream.release();}
        }
      }finally{kws.release();}result.putInt("checks",checks);finish(-1,result);return;
    }
    if(args.getString("phase","").equals("wake-action")){
      startActivitySync(new android.content.Intent(c,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));c.getSharedPreferences("wake-word",0).edit().putBoolean("enabled",true).remove("last_trigger_at").commit();
      c.startForegroundService(new android.content.Intent(c,RecordingService.class).setAction(RecordingService.START));Thread.sleep(3000);long before=System.currentTimeMillis();WakeWordEngine.activate(c);Thread.sleep(600);
      if(c.getSharedPreferences("wake-word",0).getLong("last_trigger_at",0)<before)throw new IOException("wake action missing");checks++;
      c.startService(new android.content.Intent(c,RecordingService.class).setAction(RecordingService.PAUSE));Thread.sleep(700);long trigger=c.getSharedPreferences("wake-word",0).getLong("last_trigger_at",0);WakeWordEngine.activate(c);Thread.sleep(300);if(c.getSharedPreferences("wake-word",0).getLong("last_trigger_at",0)!=trigger)throw new IOException("paused capture accepted wake");checks++;
      if(!c.getSharedPreferences("wake-word",0).getString("status","").equals("off"))throw new IOException("pause did not close KWS");checks++;
      c.startService(new android.content.Intent(c,RecordingService.class).setAction(RecordingService.STOP));Thread.sleep(600);c.getSharedPreferences("wake-word",0).edit().putBoolean("enabled",false).commit();result.putInt("checks",checks);finish(-1,result);return;
    }
    if(args.getString("phase","").equals("restart-prepare")){
      startActivitySync(new android.content.Intent(c,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
      c.startForegroundService(new android.content.Intent(c,RecordingService.class).setAction(RecordingService.START));Thread.sleep(2200);
      if(!c.getSharedPreferences("recorder",0).getString("state","").equals("recording"))throw new IOException("restart setup");
      result.putInt("checks",1);finish(-1,result);return;
    }
    if(args.getString("phase","").equals("capture")||args.getString("phase","").equals("capture-shared")){
      c.getSharedPreferences("wake-word",0).edit().putBoolean("enabled",args.getString("phase","").equals("capture-shared")).commit();
      startActivitySync(new android.content.Intent(c,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
      File dir=new File(c.getFilesDir(),"recordings");dir.mkdirs();java.util.Set<String> before=new java.util.HashSet<>();File[] old=dir.listFiles();if(old!=null)for(File f:old)before.add(f.getName());
      c.startForegroundService(new android.content.Intent(c,RecordingService.class).setAction(RecordingService.START));Thread.sleep(2200);c.startService(new android.content.Intent(c,RecordingService.class).setAction(RecordingService.MARK));Thread.sleep(1000);c.startService(new android.content.Intent(c,RecordingService.class).setAction(RecordingService.PAUSE));Thread.sleep(800);
      if(!c.getSharedPreferences("recorder",0).getString("state","").equals("paused"))throw new IOException("pause did not save");checks++;
      c.startForegroundService(new android.content.Intent(c,RecordingService.class).setAction(RecordingService.START));Thread.sleep(1800);c.startService(new android.content.Intent(c,RecordingService.class).setAction(RecordingService.STOP));Thread.sleep(800);
      if(!c.getSharedPreferences("recorder",0).getString("state","").equals("idle"))throw new IOException("stop did not save");checks++;
      File[] created=dir.listFiles((d,n)->n.endsWith(".m4a.json")&&!before.contains(n));if(created==null||created.length!=2)throw new IOException("two real encoded segments missing");checks++;
      boolean marked=false;for(File f:created){JSONObject m=new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),"UTF-8"));File audio=new File(dir,m.getString("audio_file"));android.media.MediaMetadataRetriever media=new android.media.MediaMetadataRetriever();try{media.setDataSource(audio.getPath());if(Long.parseLong(media.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION))<500)throw new IOException("recorded audio not decodable");checks++;}finally{media.release();}if(m.getJSONArray("markers").length()>0)marked=true;if(!m.has("gap_before_ms"))throw new IOException("gap metadata absent");checks++;}
      if(!marked)throw new IOException("marker was lost");checks++;File[] files=dir.listFiles();if(files!=null)for(File f:files)if(!before.contains(f.getName()))f.delete();
      result.putInt("checks",checks);finish(-1,result);return;
    }
    if(args.getString("phase","").equals("recovery")){
      File dir=new File(c.getFilesDir(),"recordings");dir.mkdirs();
      for(boolean renamed:new boolean[]{false,true}){
        String name="recovery-fixture-"+(renamed?"renamed":"pending")+".m4a";File part=new File(dir,name+".part"),complete=new File(dir,name),journal=new File(dir,name+".part.capture.json"),metadata=new File(dir,name+".json");part.delete();complete.delete();journal.delete();metadata.delete();
        try(FileOutputStream out=new FileOutputStream(renamed?complete:part)){out.write(wave());}
        try(FileOutputStream out=new FileOutputStream(journal)){out.write(new JSONObject().put("session_id","recovery-test").put("sequence",1).put("started_at_epoch_ms",System.currentTimeMillis()-1000).toString().getBytes("UTF-8"));}
        RecordingRecovery.inspect(c);
        if(!complete.isFile()||!metadata.isFile()||part.exists()||journal.exists())throw new IOException("Recover complete interrupted segment");checks++;
        JSONObject m=new JSONObject(new String(java.nio.file.Files.readAllBytes(metadata.toPath()),"UTF-8"));if(m.getLong("capture_elapsed_ms")<990||!m.getString("reason").equals("recovered_after_interruption"))throw new IOException("Recovery metadata");checks++;complete.delete();metadata.delete();
      }
      File broken=new File(dir,"broken-fixture.m4a.part"),journal=new File(dir,"broken-fixture.m4a.part.capture.json");try(FileOutputStream out=new FileOutputStream(broken)){out.write(new byte[]{1,2,3});}try(FileOutputStream out=new FileOutputStream(journal)){out.write(new JSONObject().put("session_id","broken").put("sequence",1).put("started_at_epoch_ms",1).toString().getBytes("UTF-8"));}
      RecordingRecovery.inspect(c);if(!broken.exists()||!journal.exists())throw new IOException("Unrecoverable evidence deleted");checks++;broken.delete();journal.delete();if(RecordingService.alive)throw new IOException("Recovery opened microphone");checks++;
      result.putInt("checks",checks);finish(-1,result);return;
    }
    if(args.getString("phase","").equals("sensors")){
      android.content.SharedPreferences prefs=c.getSharedPreferences("capture-policy",0);String old=prefs.getString("json","{}");
      HandlerThread thread=new HandlerThread("sensor-fixture");thread.start();Handler handler=new Handler(thread.getLooper());java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);Throwable[] failure=new Throwable[1];
      File file=new File(c.getCacheDir(),"sensor-fixture.m4a.part");File log=new File(c.getCacheDir(),"sensor-fixture.context.jsonl");
      prefs.edit().putString("json",new JSONObject().put("revision",99).put("light",true).put("pressure",true).put("heart",true).toString()).commit();
      handler.post(()->{try{ContextRecorder recorder=new ContextRecorder(c,handler,file);handler.postDelayed(()->{try{recorder.close();}catch(Throwable e){failure[0]=e;}finally{done.countDown();}},4500);}catch(Throwable e){failure[0]=e;done.countDown();}});
      try{if(!done.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Sensor timeout");if(failure[0]!=null)throw new IOException(failure[0]);boolean light=false,pressure=false,heart=false;
        try(BufferedReader in=new BufferedReader(new FileReader(log))){String line;while((line=in.readLine())!=null){JSONObject row=new JSONObject(line);if(row.getString("type").equals("light")&&!row.isNull("value"))light=true;if(row.getString("type").equals("pressure")&&!row.isNull("value"))pressure=true;if(row.getString("type").equals("heart_rate")&&row.has("missing_reason"))heart=true;}}
        if(!light||!pressure||!heart)throw new IOException("Expected light pressure and explicit unavailable heart");checks+=3;
      }finally{prefs.edit().putString("json",old).commit();thread.quitSafely();log.delete();}
      result.putInt("checks",checks);finish(-1,result);return;
    }
    String peer="AA:BB:CC:DD:EE:FF";ByteArrayOutputStream pairingReply=new ByteArrayOutputStream();DataOutputStream reply=new DataOutputStream(pairingReply);reply.writeUTF("PAIRED");reply.writeUTF("synthetic-bluetooth-token");
    c.getSharedPreferences("phone-receipts",0).edit().putString("old-peer","stale").commit();
    WatchLink.pairSession(c,peer,"12345678",new DataInputStream(new ByteArrayInputStream(pairingReply.toByteArray())),new DataOutputStream(new ByteArrayOutputStream()));
    if(!WatchLink.paired(c)||c.getSharedPreferences("phone-receipts",0).contains("old-peer"))throw new IOException("Pairing state or old receipt");checks++;
    if(!LocalSecurity.open(WatchLink.prefs(c).getString("token","")).equals("synthetic-bluetooth-token"))throw new IOException("Encrypted pairing token");checks++;
    File dir=new File(c.getFilesDir(),"recordings");dir.mkdirs();File audio=new File(dir,"bluetooth-session_1.m4a");try(FileOutputStream out=new FileOutputStream(audio)){out.write(wave());}
    JSONObject metadata=new JSONObject().put("schema_version",1).put("session_id","bluetooth-fixture").put("audio_file",audio.getName()).put("bytes",audio.length()).put("capture_elapsed_ms",1000);
    try(FileOutputStream out=new FileOutputStream(audio.getPath()+".json")){out.write(metadata.toString().getBytes("UTF-8"));}
    boolean modern=!args.getString("phase","").equals("legacy");WatchLink.prefs(c).edit().putBoolean("archive_protocol",modern).commit();ByteArrayOutputStream inbound=new ByteArrayOutputStream();DataOutputStream server=new DataOutputStream(inbound);server.writeUTF("READY");if(modern)ArchivePolicy.write(server,new JSONObject().put("version",1).put("revision",9));else server.writeUTF(new JSONObject().put("version",1).put("revision",9).toString());
    File[] files=dir.listFiles((d,n)->n.endsWith(".m4a")||n.endsWith(".context.jsonl"));java.util.Arrays.sort(files);
    for(File file:files){if(file.getName().endsWith(".m4a")&&!new File(file.getPath()+".json").isFile())continue;server.writeUTF("DURABLE");server.writeUTF(file.getName());server.writeUTF(LocalSecurity.hash(file));}server.writeUTF("DONE");
    c.getSharedPreferences("phone-receipts",0).edit().clear().commit();ByteArrayOutputStream uploaded=new ByteArrayOutputStream();WatchLink.syncSession(c,new DataInputStream(new ByteArrayInputStream(inbound.toByteArray())),new DataOutputStream(uploaded));
    if(!c.getSharedPreferences("phone-receipts",0).getString(audio.getName(),"").equals(LocalSecurity.hash(audio)))throw new IOException("Receipt missing");checks++;
    if(!audio.exists())throw new IOException("Original removed");checks++;
    inbound.reset();server=new DataOutputStream(inbound);server.writeUTF("READY");if(modern)ArchivePolicy.write(server,new JSONObject().put("version",1).put("revision",9));else server.writeUTF(new JSONObject().put("version",1).put("revision",9).toString());server.writeUTF("DONE");ByteArrayOutputStream repeated=new ByteArrayOutputStream();
    WatchLink.syncSession(c,new DataInputStream(new ByteArrayInputStream(inbound.toByteArray())),new DataOutputStream(repeated));if(repeated.size()>=uploaded.size())throw new IOException("Receipt dedup missing");checks++;
    c.getSharedPreferences("phone-receipts",0).edit().clear().commit();inbound.reset();server=new DataOutputStream(inbound);server.writeUTF("READY");if(modern)ArchivePolicy.write(server,new JSONObject().put("version",1).put("revision",9));else server.writeUTF(new JSONObject().put("version",1).put("revision",9).toString());boolean disconnected=false;
    try{WatchLink.syncSession(c,new DataInputStream(new ByteArrayInputStream(inbound.toByteArray())),new DataOutputStream(new ByteArrayOutputStream()));}catch(EOFException e){disconnected=true;}
    if(!disconnected||!audio.exists()||!c.getSharedPreferences("phone-receipts",0).getAll().isEmpty())throw new IOException("Disconnected transfer acknowledged or removed original");checks++;
    result.putInt("checks",checks);finish(-1,result);
 }catch(Throwable e){result.putInt("checks",checks);result.putString("error",e.toString());finish(1,result);}}
 private float[] pcm(File file)throws Exception{try(RandomAccessFile in=new RandomAccessFile(file,"r")){if(in.readInt()!=0x52494646)throw new IOException("wave header");in.skipBytes(4);if(in.readInt()!=0x57415645)throw new IOException("wave format");while(in.getFilePointer()+8<=in.length()){int id=in.readInt();int size=Integer.reverseBytes(in.readInt());if(size<0||size>2000000)throw new IOException("fixture size");if(id==0x64617461){float[] out=new float[size/2];for(int i=0;i<out.length;i++)out[i]=Short.reverseBytes(in.readShort())/32768f;return out;}in.seek(in.getFilePointer()+size+(size%2));}throw new IOException("wave data missing");}}
 private static Object field(Object o,String name)throws Exception{java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 private RecordingService service()throws Exception{Object[] found={null};runOnMainSync(()->{try{Class<?> cls=Class.forName("android.app.ActivityThread");java.lang.reflect.Method current=cls.getDeclaredMethod("currentActivityThread");current.setAccessible(true);Object at=current.invoke(null);java.lang.reflect.Field f=cls.getDeclaredField("mServices");f.setAccessible(true);for(Object s:((java.util.Map<?,?>)f.get(at)).values())if(s instanceof RecordingService)found[0]=s;}catch(Exception e){throw new RuntimeException(e);}});if(found[0]==null)throw new IOException("service absent");return (RecordingService)found[0];}
 private byte[] wave(){int n=16000;ByteBuffer b=ByteBuffer.allocate(44+n*2).order(ByteOrder.LITTLE_ENDIAN);b.put(new byte[]{'R','I','F','F'}).putInt(36+n*2).put(new byte[]{'W','A','V','E','f','m','t',' '});b.putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(n*2);return b.array();}
}
