package org.diy.lifecore;

import android.bluetooth.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.*;
import java.util.*;

/** Secure bonded RFCOMM only. No discovery, Wi-Fi, GMS or insecure socket fallback. */
public final class BluetoothTransport {
    public static final UUID SERVICE_UUID=UUID.fromString("5c52d277-a7be-4de4-8e21-5cc862308f10");
    private BluetoothTransport(){}
    public static BluetoothAdapter adapter(Context context)throws IOException {
        if(Build.VERSION.SDK_INT>=31&&context.getApplicationInfo().targetSdkVersion>=31
                &&context.checkSelfPermission("android.permission.BLUETOOTH_CONNECT")!=PackageManager.PERMISSION_GRANTED)
            throw new IOException("请允许附近设备权限");
        BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null)throw new IOException("这台设备不支持蓝牙");
        if(!adapter.isEnabled())throw new IOException("请先打开系统蓝牙");
        return adapter;
    }
    public static String address(String address)throws IOException {
        if(address==null||!address.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))throw new IOException("蓝牙设备地址无效");
        return address.toUpperCase(Locale.ROOT);
    }
    public static List<BluetoothDevice> bonded(Context context)throws IOException {
        ArrayList<BluetoothDevice> devices=new ArrayList<>(adapter(context).getBondedDevices());
        Collections.sort(devices,(a,b)->a.getAddress().compareTo(b.getAddress()));return devices;
    }
    public static Channel connect(Context context,String address)throws IOException {
        BluetoothDevice device=adapter(context).getRemoteDevice(address(address));
        if(device.getBondState()!=BluetoothDevice.BOND_BONDED)throw new IOException("请先在系统中完成手机与手表的蓝牙配对");
        Channel channel=new Channel(device.createRfcommSocketToServiceRecord(SERVICE_UUID));
        try{android.util.Log.d("LifeBluetooth","RFCOMM connect begin");channel.socket.connect();channel.touch();android.util.Log.d("LifeBluetooth","RFCOMM connected");return channel;}catch(IOException|RuntimeException e){channel.close();throw e;}
    }
    /** BluetoothSocket has no SO_TIMEOUT; a monotonic watchdog cancels stalled IO. */
    public static final class Channel implements Closeable {
        private final BluetoothSocket socket;
        private final Timer timer=new Timer("liferecord-bluetooth-timeout",true);
        private volatile long activity=SystemClock.elapsedRealtime();
        private volatile boolean closed;
        public Channel(BluetoothSocket socket){this.socket=socket;timer.scheduleAtFixedRate(new TimerTask(){public void run(){if(SystemClock.elapsedRealtime()-activity>45000)try{close();}catch(IOException ignored){}}},1000,1000);}
        private void touch(){activity=SystemClock.elapsedRealtime();}
        public String peer(){return socket.getRemoteDevice().getAddress();}
        public InputStream input()throws IOException{return new FilterInputStream(socket.getInputStream()){
            public int read()throws IOException{int n=in.read();if(n>=0)touch();return n;}
            public int read(byte[] b,int off,int len)throws IOException{android.util.Log.d("LifeBluetooth","read begin requested="+len);int n=in.read(b,off,len);if(n>0)touch();android.util.Log.d("LifeBluetooth","read completed bytes="+n);return n;}
        };}
        public OutputStream output()throws IOException{return new FilterOutputStream(socket.getOutputStream()){
            public void write(int b)throws IOException{out.write(b);touch();}
            public void write(byte[] b,int off,int len)throws IOException{android.util.Log.d("LifeBluetooth","write begin bytes="+len);out.write(b,off,len);touch();android.util.Log.d("LifeBluetooth","write completed");}
            public void flush()throws IOException{out.flush();touch();}
        };}
        public synchronized void close()throws IOException{if(closed)return;closed=true;timer.cancel();socket.close();}
    }
}
