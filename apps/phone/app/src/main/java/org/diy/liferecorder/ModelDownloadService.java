package org.diy.liferecorder;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import org.json.*;
import org.diy.lifecore.LocalSecurity;

/** Explicit user downloads only. HTTPS, exact length/hash, resumable staging, atomic installation. */
public final class ModelDownloadService extends Service {
    static volatile String active="",status="";static volatile long downloadDone,downloadTotal;
    private volatile boolean paused;
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean occupied=new java.util.concurrent.atomic.AtomicBoolean();
    private long lastNotification;
    public void onCreate(){super.onCreate();NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);n.createNotificationChannel(new NotificationChannel("offline-download","离线资源下载",NotificationManager.IMPORTANCE_LOW));startForeground(74,notification("准备离线资源…"));}
    private Notification notification(String line){
        PendingIntent stop=PendingIntent.getService(this,74,new Intent(this,ModelDownloadService.class).setAction("pause"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open=PendingIntent.getActivity(this,74,new Intent(this,OfflineActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"offline-download").setProgress(1000,downloadTotal>0?(int)Math.min(1000,downloadDone*1000/downloadTotal):0,downloadTotal<=0).setSmallIcon(R.drawable.ic_record).setContentTitle(AppLanguage.text(this,"MindPebble · 离线资源")).setContentText(AppLanguage.text(this,line)).setContentIntent(open).setOngoing(true).addAction(0,AppLanguage.text(this,"暂停"),stop).build();
    }
    private void progress(String line){status=line;long now=SystemClock.elapsedRealtime();if(now-lastNotification>1000){lastNotification=now;((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(74,notification(line));}}
    private void check()throws IOException{if(paused||Thread.currentThread().isInterrupted())throw new IOException("下载已暂停，可继续");}
    public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"pause".equals(intent.getAction())){paused=true;if(!occupied.get())stopSelfResult(startId);return START_NOT_STICKY;}
        if(intent==null){stopSelfResult(startId);return START_NOT_STICKY;}
        if(!occupied.compareAndSet(false,true))return START_NOT_STICKY;
        String id=intent.getStringExtra("pack");downloadDone=0;downloadTotal=0;paused=false;active=id==null?"":id;
        worker.execute(()->{
            try{OfflinePacks packs=new OfflinePacks(this);packs.pack(id);packs.prefs.edit().remove(id+"-error").commit();
                if(!packs.installed(id))download(packs,id);
                check();downloadTotal=0;progress("正在验证模型能否在本机运行…");
                JSONObject measurement=OfflineSpeech.benchmark(packs,id,()->paused);
                check();packs.prefs.edit().putString(id+"-tested",packs.pack(id).getString("version")).putString(id+"-benchmark",measurement.toString()).remove(id+"-error").commit();
                status="运行验证通过 · 可离线使用";
            }catch(Throwable e){status=paused?"已暂停，点继续下载":"未完成："+(friendlyError(e));
                if(id!=null)getSharedPreferences("offline-packs-v1",0).edit().putString(id+"-error",status).commit();
            }finally{active="";occupied.set(false);stopForeground(true);stopSelf();}
        });return START_NOT_STICKY;
    }
    static String friendlyError(Throwable error){
        if(error instanceof java.net.UnknownHostException||error instanceof java.net.ConnectException||error instanceof java.net.SocketTimeoutException)return "下载源连接失败，已保留进度；可切换原站 / 国内镜像后继续";
        return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
    }
    void download(OfflinePacks packs,String id)throws Exception{
        JSONObject p=packs.pack(id);File stage=new File(packs.root,id+".partial");if(!stage.isDirectory()&&!stage.mkdirs())throw new IOException("无法创建下载目录");
        JSONArray fs=p.getJSONArray("files");long total=packs.bytes(id),done=0;
        for(int i=0;i<fs.length();i++){
            check();JSONObject f=fs.getJSONObject(i);String name=f.getString("name");
            if(!name.matches("[a-zA-Z0-9_.-]+"))throw new IOException("资源文件名无效");
            File partial=new File(stage,name+".part");File staged=new File(stage,name);if(!partial.exists()&&staged.isFile()&&!staged.renameTo(partial))throw new IOException("无法恢复安装进度");long expected=f.getLong("bytes");
            if(partial.length()>expected&&!partial.delete())throw new IOException("无法清理损坏下载");
            if(partial.length()==expected&&!LocalSecurity.hash(partial).equals(f.getString("sha256")))partial.delete();
            if(partial.length()<expected)fetch(packs.downloadUrl(f.getString("url")),partial,expected,packs,done,total);
            check();if(partial.length()!=expected||!LocalSecurity.hash(partial).equals(f.getString("sha256"))){partial.delete();throw new IOException("文件校验失败，请重试");}
            done+=expected;
        }
        check();
        synchronized(OfflinePacks.ENGINE_LOCK){
            check();File destination=packs.directory(id);if(packs.installed(id))return;
            for(int i=0;i<fs.length();i++){String name=fs.getJSONObject(i).getString("name");if(!new File(stage,name+".part").renameTo(new File(stage,name)))throw new IOException("无法提交资源文件");}
            packs.verify(id,stage);
            try(FileOutputStream out=new FileOutputStream(new File(stage,"installed.json"))){out.write(new JSONObject().put("version",p.getString("version")).toString().getBytes("UTF-8"));out.getFD().sync();}
            OfflinePacks.deleteTree(destination);if(!stage.renameTo(destination))throw new IOException("资源安装失败");LocalSecurity.syncDirectory(packs.root);
        }
    }
    private void fetch(String address,File dest,long expected,OfflinePacks packs,long done,long total)throws Exception{
        long offset=dest.length();if(packs.root.getUsableSpace()<expected-offset+128L*1024*1024)throw new IOException("剩余空间不足，请至少预留 128 MB");
        HttpURLConnection connection=open(address,offset);
        try{
            int code=connection.getResponseCode();
            if(code==200&&offset>0){try(FileOutputStream truncate=new FileOutputStream(dest)){truncate.getFD().sync();}offset=0;}
            else if(code==206){String range=connection.getHeaderField("Content-Range");if(range==null||!range.startsWith("bytes "+offset+"-")||!range.endsWith("/"+expected))throw new IOException("下载续传响应无效");}
            else if(code!=200)throw new IOException("下载源暂不可用（HTTP "+code+"），可稍后继续");
            try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(dest,offset>0)){
                byte[] b=new byte[65536];long received=offset;int n;
                try{while((n=in.read(b))!=-1){check();if(received+n>expected)throw new IOException("资源长度与目录不符");out.write(b,0,n);received+=n;downloadDone=done+received;downloadTotal=total;if(packs.root.getUsableSpace()<64L*1024*1024)throw new IOException("剩余空间不足");progress(String.format(java.util.Locale.CHINA,"下载 %.0f%% · %.1f / %.1f MB",100.0*(done+received)/total,(done+received)/1048576.0,total/1048576.0));}}finally{out.getFD().sync();}
            }
        }finally{connection.disconnect();}
    }
    static HttpURLConnection open(String address,long offset)throws Exception{
        for(int redirect=0;redirect<8;redirect++){
            URL url=new URL(address);if(!url.getProtocol().equals("https")||url.getUserInfo()!=null)throw new IOException("资源仅允许 HTTPS 下载");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(15000);c.setRequestProperty("Accept-Encoding","identity");if(offset>0)c.setRequestProperty("Range","bytes="+offset+"-");
            int code=c.getResponseCode();if(code==301||code==302||code==303||code==307||code==308){String location=c.getHeaderField("Location");c.disconnect();if(location==null)throw new IOException("下载重定向缺少地址");address=new URL(url,location).toString();continue;}return c;
        }throw new IOException("下载重定向过多");
    }
    public void onDestroy(){paused=true;worker.shutdown();super.onDestroy();}
    public IBinder onBind(Intent i){return null;}
}
