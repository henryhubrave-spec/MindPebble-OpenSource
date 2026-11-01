package org.diy.watchrecorder;

import android.content.*;
import java.io.*;
import java.util.*;
import org.json.*;
import org.diy.lifecore.*;

/** Bonded encrypted RFCOMM plus an app token. Failed sync never removes originals. */
final class WatchLink {
    static final int MAGIC=0x4c523032;
    static final Object LOCK=new Object();
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("phone-link",0);}
    static String device(Context c){SharedPreferences p=prefs(c);String id=p.getString("id","");if(id.isEmpty()){id=UUID.randomUUID().toString();p.edit().putString("id",id).commit();}return id;}
    static boolean paired(Context c){return prefs(c).contains("token")&&prefs(c).contains("bluetooth_address");}
    static void pair(Context c,String address,String code)throws Exception {
        synchronized(LOCK){
            try(BluetoothTransport.Channel channel=BluetoothTransport.connect(c,address)){
                pairSession(c,address,code,new DataInputStream(new BufferedInputStream(channel.input(),16384)),new DataOutputStream(new BufferedOutputStream(channel.output(),16384)));
            }
        }
    }
    static void pairSession(Context c,String address,String code,DataInputStream in,DataOutputStream out)throws Exception {
        address=BluetoothTransport.address(address);if(!code.matches("[0-9]{8}"))throw new IOException("请填写手机当前 8 位配对码");
        out.writeInt(MAGIC);out.writeUTF("PAIR");out.writeUTF(device(c));out.writeUTF(code);out.flush();
        if(!in.readUTF().equals("PAIRED"))throw new IOException("配对码失效或不正确");
        if(!prefs(c).edit().putString("bluetooth_address",address).putString("token",LocalSecurity.seal(in.readUTF())).remove("address").remove("pin").remove("archive_protocol").commit())throw new IOException("无法保存配对");
        c.getSharedPreferences("archive-processed",0).edit().clear().commit();if(!c.getSharedPreferences("phone-receipts",0).edit().clear().commit())throw new IOException("无法清除旧手机回执，请重新配对");
    }
    static void sync(Context context)throws Exception {
        synchronized(LOCK){
            Context c=context.getApplicationContext();if(!paired(c))throw new IOException("请先在连接页完成蓝牙配对");
            prefs(c).edit().putLong("attempt_elapsed",android.os.SystemClock.elapsedRealtime()).putInt("attempt_boot",WatchSyncJob.boot(c)).commit();ArchivePolicy.prune(c);
            android.os.PowerManager.WakeLock wake=((android.os.PowerManager)c.getSystemService(Context.POWER_SERVICE)).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"MindPebble:sync");wake.acquire();try{boolean before=prefs(c).getBoolean("archive_protocol",false);try(BluetoothTransport.Channel channel=BluetoothTransport.connect(c,prefs(c).getString("bluetooth_address",""))){
                syncSession(c,new DataInputStream(new BufferedInputStream(channel.input(),16384)),new DataOutputStream(new BufferedOutputStream(channel.output(),16384)));
            }if(!before&&prefs(c).getBoolean("archive_protocol",false))try(BluetoothTransport.Channel channel=BluetoothTransport.connect(c,prefs(c).getString("bluetooth_address",""))){syncSession(c,new DataInputStream(new BufferedInputStream(channel.input(),16384)),new DataOutputStream(new BufferedOutputStream(channel.output(),16384)));}
            }catch(Exception e){prefs(c).edit().putString("status","蓝牙同步未完成 · 原件保留").apply();throw e;}finally{if(wake.isHeld())wake.release();}
        }
    }
    static void syncSession(Context c,DataInputStream in,DataOutputStream out)throws Exception {
        boolean managed=prefs(c).getBoolean("archive_protocol",false);out.writeInt(MAGIC);out.writeUTF(managed?"SYNC2":"SYNC");out.writeUTF(device(c));out.writeUTF(LocalSecurity.open(prefs(c).getString("token","")));out.flush();
        if(!in.readUTF().equals("READY"))throw new IOException("手机未认可配对，请重新输入配对码");
            JSONObject policy=managed?ArchivePolicy.read(in):new JSONObject(in.readUTF());if(policy.optInt("archive_protocol")>=2)prefs(c).edit().putBoolean("archive_protocol",true).commit();if(policy.getInt("version")!=1)throw new IOException("策略版本不支持");
            JSONArray archiveAcks=ArchivePolicy.apply(c,policy.optJSONObject("archive"));policy.remove("archive");
            // VAD execution is phone-local. Watch continues to retain complete source audio.
            c.getSharedPreferences("capture-policy",0).edit().putString("language",policy.optString("language","zh")).putString("json",policy.toString()).commit();
            File dir=new File(c.getFilesDir(),"recordings");File[] files=dir.listFiles((d,n)->n.endsWith(".m4a")||n.endsWith(".context.jsonl"));if(files==null)files=new File[0];Arrays.sort(files);
            SharedPreferences receipts=c.getSharedPreferences("phone-receipts",0);
            for(File file:files){
                String sha=FinalizedDigest.get(c,file);if(sha.equals(receipts.getString(file.getName(),"")))continue;
                boolean audio=file.getName().endsWith(".m4a");File metadata=new File(file.getPath()+".json");if(audio&&!metadata.isFile())continue;
                JSONObject manifest=new JSONObject().put("name",file.getName()).put("kind",audio?"audio":"context").put("title",WatchLanguage.text(c,"生活记录 · ")+file.getName().substring(0,Math.min(15,file.getName().length())));
                if(audio)manifest.put("metadata",new JSONObject(new String(java.nio.file.Files.readAllBytes(metadata.toPath()),"UTF-8")));
                out.writeBoolean(true);out.writeUTF(manifest.toString());out.writeLong(file.length());out.writeUTF(sha);
                try(InputStream source=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=source.read(buffer))!=-1)out.write(buffer,0,n);}out.flush();
                String result=in.readUTF();String name=in.readUTF(),hash=in.readUTF();if(!(result.equals("DURABLE")||result.equals("GONE")||result.equals("SPEECH_ONLY"))||!name.equals(file.getName())||!hash.equals(sha))throw new IOException("手机未确认保存");
                if(!receipts.edit().putString(name,sha).putString(name+".state",result).commit())throw new IOException("回执未保存");
            }
            out.writeBoolean(false);out.writeLong(policy.getLong("revision"));out.writeLong(c.getSharedPreferences("capture-policy",0).getLong("effective_revision",0));if(managed)ArchivePolicy.write(out,ArchivePolicy.report(c,archiveAcks));out.flush();if(!in.readUTF().equals("DONE"))throw new IOException("同步未完成");
            prefs(c).edit().putString("status","已同步 · 原件保留").putLong("last",System.currentTimeMillis()).commit();
    }
}
