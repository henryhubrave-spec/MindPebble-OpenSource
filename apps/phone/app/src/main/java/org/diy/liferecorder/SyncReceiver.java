package org.diy.liferecorder;

import android.content.*;
import android.os.SystemClock;
import java.io.*;
import java.util.UUID;
import org.json.*;
import org.diy.lifecore.*;

/** Transport-independent session, invoked only after a secure bonded socket is accepted. */
final class SyncReceiver {
    private final Context context;
    SyncReceiver(Context context){this.context=context;}
    void handle(DataInputStream in,DataOutputStream out,String remoteAddress)throws Exception {
        String peer=BluetoothTransport.address(remoteAddress);
            SyncService.phase("正在读取手表握手");
            if(in.readInt()!=SyncService.MAGIC)throw new IOException("Protocol");String mode=in.readUTF();
            SyncService.phase("正在读取手表身份");String device=in.readUTF();if(!device.matches("[0-9a-f-]{36}"))throw new IOException("Device");
            SharedPreferences prefs=context.getSharedPreferences("device-sync",0);
            if(mode.equals("PAIR")){
                SyncService.phase("正在读取 App 配对码");String entered=in.readUTF();synchronized(SyncService.class){
                    if(SyncService.code.isEmpty()||SystemClock.elapsedRealtime()>SyncService.codeUntil||SyncService.failedCodes>=5||!LocalSecurity.equalsSecret(entered,SyncService.code)){SyncService.failedCodes++;out.writeUTF("DENIED");out.flush();return;}
                    SyncService.phase("正在保存本机配对凭据");String token=LocalSecurity.randomToken();if(!prefs.edit().putString("bluetooth_peer",peer).putString("device",device).putString("token",LocalSecurity.seal(token)).commit())throw new IOException("Credential storage");
                    SyncService.phase("正在回复配对结果");SyncService.code="";out.writeUTF("PAIRED");out.writeUTF(token);out.flush();SyncService.status="蓝牙手表已配对";return;
                }
            }
            boolean managed=mode.equals("SYNC2");if(!managed&&!mode.equals("SYNC"))throw new IOException("Mode");SyncService.phase("正在验证同步凭据");String token=in.readUTF();
            if(!peer.equals(prefs.getString("bluetooth_peer",""))||!device.equals(prefs.getString("device",""))||!prefs.contains("token")||!LocalSecurity.equalsSecret(LocalSecurity.open(prefs.getString("token","")),token)){out.writeUTF("DENIED");out.flush();return;}
            TransferProgress.begin();out.writeUTF("READY");JSONObject policy=SyncService.policy(context);if(managed)try(RecordStore db=new RecordStore(context)){policy.put("archive",WatchArchive.policy(db,device));}if(managed){byte[] policyBytes=policy.toString().getBytes("UTF-8");out.writeInt(policyBytes.length);out.write(policyBytes);}else out.writeUTF(policy.toString());out.flush();
            try(RecordStore store=new RecordStore(context)){
                int count=0;
                while(in.readBoolean()){
                    SyncService.phase("正在接收手表文件");JSONObject manifest=new JSONObject(in.readUTF());long length=in.readLong();String sha=in.readUTF();
                    if(length<=0||length>32L*1024*1024||!sha.matches("[a-f0-9]{64}")||store.media.getUsableSpace()<length+64L*1024*1024)throw new IOException("Invalid size/disk");
                    String name=manifest.getString("name");if(!name.matches("[A-Za-z0-9_+.:-]{1,220}"))throw new IOException("Filename");
                    String id=UUID.nameUUIDFromBytes((device+":"+name).getBytes("UTF-8")).toString();File temp=File.createTempFile("sync-",".part",store.media);
                    TransferProgress.update(name,0,length,"接收中");String receipt;
                    try{
                        try(FileOutputStream file=new FileOutputStream(temp)){byte[] buffer=new byte[65536];long remaining=length;while(remaining>0){int n=in.read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new EOFException();file.write(buffer,0,n);remaining-=n;TransferProgress.update(name,length-remaining,length,"接收中");}file.getFD().sync();}
                        TransferProgress.update(name,length,length,"校验并保存");if(!LocalSecurity.hash(temp).equals(sha))throw new IOException("Hash mismatch");
                        if(manifest.optString("kind").equals("context")){
                            String payload=new String(java.nio.file.Files.readAllBytes(temp.toPath()),"UTF-8");if(payload.length()>2000000)throw new IOException("Context too large");
                            for(String line:payload.split("\n"))if(!line.trim().isEmpty())new JSONObject(line);
                            String audioName=name.replace(".context.jsonl",".m4a");String sourceId=UUID.nameUUIDFromBytes((device+":"+audioName).getBytes("UTF-8")).toString();
                            store.getWritableDatabase().beginTransaction();try{if(store.deleted(sourceId))receipt="GONE";
                            else {ContentValues v=new ContentValues();v.put("id",id);v.put("source",sourceId);v.put("payload",payload);v.put("created",System.currentTimeMillis());store.getWritableDatabase().insertWithOnConflict("context",null,v,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);receipt="DURABLE";}store.getWritableDatabase().setTransactionSuccessful();}finally{store.getWritableDatabase().endTransaction();}
                        }else {
                            JSONObject metadata=manifest.getJSONObject("metadata");metadata.put("watch_device",device);
                            if(metadata.optInt("schema_version")!=1||!metadata.optString("audio_file").equals(name)||metadata.optLong("bytes",-1)!=length||metadata.optLong("capture_elapsed_ms",-1)<0)throw new IOException("Unsupported or inconsistent audio metadata");
                            receipt=store.receive(temp,id,manifest.optString("title","手表生活记录"),sha,metadata.toString());
                        }
                    }finally{temp.delete();}
                    TransferProgress.saved(length);out.writeUTF(receipt);out.writeUTF(name);out.writeUTF(sha);out.flush();
                    if(receipt.equals("DURABLE")&&!manifest.optString("kind").equals("context")&&new SecureSettings(context).vadRequested()){
                        if(store.audio(id).optString("vad_map").isEmpty()&&store.rows("SELECT id FROM jobs WHERE source=? AND type='vad' AND status IN ('queued','running')",id).length()==0){store.addJob(id,"vad",new JSONObject());new android.os.Handler(android.os.Looper.getMainLooper()).post(()->context.startForegroundService(new Intent(context,AgentService.class)));}
                    }
                    if(receipt.equals("DURABLE")&&!manifest.optString("kind").equals("context")){try{OfflineScheduler.enqueue(store,id);OfflineScheduler.schedule(context);}catch(Exception ignored){}}
                    ContextReminder.check(context,store);count++;SyncService.status="已到手机 "+count+" 段/份";
                }
                long revision=in.readLong();prefs.edit().putLong("last_sync_at",System.currentTimeMillis()).putLong("watch_policy_ack",revision).putLong("watch_capture_revision",in.readLong()).apply();if(managed)WatchArchive.accept(store,device,readReport(in));out.writeUTF("DONE");out.flush();TransferProgress.finish();OfflineScheduler.runBackground(context);
            }
    }
    private JSONObject readReport(DataInputStream in)throws Exception{int length=in.readInt();if(length<2||length>8*1024*1024)throw new IOException("Invalid archive report");byte[] bytes=new byte[length];in.readFully(bytes);return new JSONObject(new String(bytes,"UTF-8"));}

}
