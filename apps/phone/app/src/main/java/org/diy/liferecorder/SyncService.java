package org.diy.liferecorder;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.security.SecureRandom;
import java.util.Locale;
import org.json.JSONObject;
import org.diy.lifecore.BluetoothTransport;

/** User-started Bluetooth receiver. No TCP listener or Wi-Fi requirement. */
public final class SyncService extends Service {
    public static final int MAGIC=0x4c523032;
    public static volatile boolean running;
    public static volatile String status="蓝牙接收未开启",code="";
    public static volatile String stage="未连接",details="尚无连接错误";
    static String failure(Throwable error){String value=error.getMessage();return error.getClass().getSimpleName()+(value==null?"":": "+value.substring(0,Math.min(240,value.length())));}
    static void phase(String value){stage=value;status=value;}
    static volatile long codeUntil;
    static int failedCodes;
    private volatile BluetoothServerSocket server;
    private volatile BluetoothTransport.Channel current;
    private volatile boolean closing;
    public static synchronized void newCode(){code=String.format(Locale.ROOT,"%08d",new SecureRandom().nextInt(100000000));codeUntil=SystemClock.elapsedRealtime()+300000;failedCodes=0;}
    public static boolean codeValid(){return !code.isEmpty()&&SystemClock.elapsedRealtime()<codeUntil&&failedCodes<5;}
    public void onCreate(){super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("bluetooth-sync","手表蓝牙同步",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,71,new Intent(this,SyncService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(71,new Notification.Builder(this,"bluetooth-sync").setSmallIcon(R.drawable.ic_record).setContentTitle(AppLanguage.text(this,"手表蓝牙接收已开启")).setContentText(AppLanguage.text(this,"不用 Wi-Fi 或热点，仅接收已配对手表")).setOngoing(true).addAction(0,AppLanguage.text(this,"停止接收"),stop).build());
        getSharedPreferences("background-sync",0).edit().putBoolean("enabled",true).apply();running=true;status="正在开启蓝牙接收…";
        new Thread(()->{
            try{
                BluetoothAdapter adapter=BluetoothTransport.adapter(this);
                server=adapter.listenUsingRfcommWithServiceRecord("LifeRecord",BluetoothTransport.SERVICE_UUID);
                if(closing){server.close();return;}
                phase("等待手表蓝牙连接");details="尚无连接错误";
                while(!closing){
                    BluetoothSocket socket=server.accept();phase("已收到蓝牙连接，正在核对系统配对");
                    try{
                        try(BluetoothTransport.Channel channel=new BluetoothTransport.Channel(socket)){
                            current=channel;if(closing)break;
                            if(socket.getRemoteDevice().getBondState()!=BluetoothDevice.BOND_BONDED)throw new IOException("系统蓝牙尚未配对");
                            phase("蓝牙已连接，等待手表握手");
                            PowerManager.WakeLock wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MindPebble:receive");wake.acquire();try{new SyncReceiver(this).handle(new DataInputStream(new BufferedInputStream(channel.input(),16384)),new DataOutputStream(new BufferedOutputStream(channel.output(),16384)),channel.peer());}finally{if(wake.isHeld())wake.release();}
                        }finally{current=null;}
                    }catch(Exception e){if(!closing){TransferProgress.failed();details="阶段："+stage+"\n"+failure(e);android.util.Log.w("LifeBluetooth","Session failed at "+stage+": "+e.getClass().getSimpleName());status="蓝牙连接中断，原件保留；点连接详情查看原因";}}
                }
            }catch(Exception e){if(!closing){TransferProgress.failed();details="阶段："+stage+"\n"+failure(e);status=e instanceof IOException?e.getMessage():"无法开启蓝牙，请检查附近设备权限和系统蓝牙";}}
            finally{try{if(server!=null)server.close();}catch(IOException ignored){}running=false;stopSelf();}
        },"bluetooth-sync-receiver").start();
    }
    static JSONObject policy(Context context)throws Exception{
        SharedPreferences p=context.getSharedPreferences("capture-policy",0);SecureSettings s=new SecureSettings(context);
        return new JSONObject().put("archive_protocol",2).put("language",AppLanguage.code(context)).put("version",1).put("revision",p.getLong("revision",1)).put("vad",s.vadRequested())
           .put("heart_interval_minutes",p.getInt("heart_interval_minutes",5)).put("context_reminder",new JSONObject().put("enabled",context.getSharedPreferences("context-reminder",0).getBoolean("enabled",false)).put("hour",context.getSharedPreferences("context-reminder",0).getInt("hour",21)).put("minute",context.getSharedPreferences("context-reminder",0).getInt("minute",30)).put("lux",context.getSharedPreferences("context-reminder",0).getInt("lux",50)).put("heart",context.getSharedPreferences("context-reminder",0).getInt("heart",70)))
          .put("steps",p.getBoolean("steps",false)).put("pressure",p.getBoolean("pressure",false)).put("light",p.getBoolean("light",false)).put("heart",p.getBoolean("heart",false));
    }
    public int onStartCommand(Intent intent,int flags,int id){if(intent!=null&&"stop".equals(intent.getAction())){getSharedPreferences("background-sync",0).edit().putBoolean("enabled",false).apply();stopSelf();return START_NOT_STICKY;}return START_STICKY;}
    public void onDestroy(){boolean active=running;closing=true;running=false;try{if(server!=null)server.close();}catch(IOException ignored){}try{if(current!=null)current.close();}catch(IOException ignored){}if(active){status="蓝牙接收已关闭";if(TransferProgress.current.active)TransferProgress.failed();}stopForeground(true);super.onDestroy();}
    public IBinder onBind(Intent intent){return null;}
}
