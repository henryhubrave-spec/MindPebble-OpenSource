package org.diy.watchrecorder;

import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.*;
import android.os.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Optional local observations. Missing sensors never become zero values or diagnoses. */
final class ContextRecorder implements SensorEventListener {
    private final Context context;private final Handler handler;private final SensorManager manager;private JSONObject policy=new JSONObject();
    private File pending,complete;private FileOutputStream stream;
    private final ArrayList<Float> pressure=new ArrayList<>(),light=new ArrayList<>(),heart=new ArrayList<>();
    private final WatchContextReminder reminder=new WatchContextReminder();
    private Float steps,lastLight;private long stepsObserved,lightObserved;private boolean closed,heartActive,configured;private long lastHeartStart;
    ContextRecorder(Context c,Handler h,File audio)throws Exception{
        context=c;handler=h;manager=(SensorManager)c.getSystemService(Context.SENSOR_SERVICE);try{openOutput(audio);}catch(Exception failure){try{close();}catch(Exception ignored){}throw failure;}
    }
    void openOutput(File audio)throws Exception{
        if(closed||stream!=null)throw new IOException("环境输出状态无效");
        complete=new File(audio.getPath().replace(".m4a.part", ".context.jsonl"));pending=new File(complete.getPath()+".part");stream=new FileOutputStream(pending);
        JSONObject next=new JSONObject(context.getSharedPreferences("capture-policy",0).getString("json","{}"));
        write(new JSONObject().put("type","capabilities").put("policy",next).put("steps_available",manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)!=null).put("pressure_available",manager.getDefaultSensor(Sensor.TYPE_PRESSURE)!=null).put("light_available",manager.getDefaultSensor(Sensor.TYPE_LIGHT)!=null).put("heart_available",manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)!=null));
        if(!configured||!policy.toString().equals(next.toString())){
            boolean wasHeart=policy.optBoolean("heart");finishHeart();manager.unregisterListener(this);cancelCallbacks();policy=next;configured=true;
            if(!wasHeart)lastHeartStart=0;
            if(policy.optBoolean("steps"))listen(Sensor.TYPE_STEP_COUNTER,SensorManager.SENSOR_DELAY_NORMAL);
            if(policy.optBoolean("light"))listen(Sensor.TYPE_LIGHT,SensorManager.SENSOR_DELAY_NORMAL);
            everyMinute.run();if(policy.optBoolean("heart"))post(heartWindow,heartDelay(SystemClock.elapsedRealtime(),lastHeartStart,heartInterval()));
        }
        context.getSharedPreferences("capture-policy",0).edit().putLong("effective_revision",next.optLong("revision",0)).commit();
    }
    static long heartDelay(long now,long last,long interval){return last==0?0:Math.max(0,Math.min(interval,last+interval-now));}
    private long heartInterval(){return Math.max(1,Math.min(15,policy.optInt("heart_interval_minutes",5)))*60000L;}
    private void post(Runnable r,long delay){handler.postDelayed(r,delay);}
    private void cancelCallbacks(){handler.removeCallbacks(everyMinute);handler.removeCallbacks(heartWindow);handler.removeCallbacks(finishPressure);handler.removeCallbacks(finishHeartWindow);}
    private final Runnable finishPressure=this::finishPressure;
    private void finishPressure(){Sensor sensor=manager.getDefaultSensor(Sensor.TYPE_PRESSURE);if(sensor!=null)manager.unregisterListener(this,sensor);try{aggregate("pressure",pressure,"hPa");}catch(Exception ignored){}}
    private final Runnable finishHeartWindow=()->finishHeart();
    private final Runnable everyMinute=new Runnable(){public void run(){if(closed)return;try{
        Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));if(battery!=null){int level=battery.getIntExtra("level",-1),scale=battery.getIntExtra("scale",-1);if(level>=0&&scale>0)write(new JSONObject().put("type","device_status").put("battery_percent",Math.round(level*100f/scale)));}
        if(steps!=null)write(new JSONObject().put("type","steps_counter").put("value",steps).put("unit","steps_since_boot").put("observed_at_epoch_ms",stepsObserved));
        if(policy.optBoolean("light"))aggregate("light",light,"lux");if(policy.optBoolean("pressure")){pressure.clear();listen(Sensor.TYPE_PRESSURE,500000);post(finishPressure,3000);}
    }catch(Exception ignored){}post(this,60000);}};
    private final Runnable heartWindow=new Runnable(){public void run(){if(closed)return;lastHeartStart=SystemClock.elapsedRealtime();heart.clear();
        if(context.checkSelfPermission(android.Manifest.permission.BODY_SENSORS)==PackageManager.PERMISSION_GRANTED){heartActive=true;listen(Sensor.TYPE_HEART_RATE,SensorManager.SENSOR_DELAY_NORMAL);post(finishHeartWindow,20000);}
        else try{write(new JSONObject().put("type","heart_rate").put("missing_reason","permission_not_granted"));}catch(Exception ignored){}
        post(this,heartInterval());
    }};
    private void finishHeart(){if(!heartActive)return;heartActive=false;Sensor s=manager.getDefaultSensor(Sensor.TYPE_HEART_RATE);if(s!=null)manager.unregisterListener(this,s);try{aggregate("heart_rate",heart,"bpm");}catch(Exception ignored){}}
    private void listen(int type,int rate){Sensor s=manager.getDefaultSensor(type);if(s==null)return;try{manager.registerListener(this,s,rate,(type==Sensor.TYPE_PRESSURE||type==Sensor.TYPE_HEART_RATE)?0:60000000,handler);}catch(Exception ignored){}}
    public void onSensorChanged(SensorEvent e){if(closed||e.values.length==0||!Float.isFinite(e.values[0]))return;float value=e.values[0];
        switch(e.sensor.getType()){
            case Sensor.TYPE_STEP_COUNTER:steps=value;stepsObserved=System.currentTimeMillis();break;
            case Sensor.TYPE_PRESSURE:if(value>0&&pressure.size()<64)pressure.add(value);break;
            case Sensor.TYPE_LIGHT:if(value>=0){lastLight=value;lightObserved=System.currentTimeMillis();if(light.size()<256)light.add(value);}break;
            case Sensor.TYPE_HEART_RATE:if(value>0&&e.accuracy!=SensorManager.SENSOR_STATUS_UNRELIABLE){heart.add(value);if(heart.size()>=3)finishHeart();}break;
        }
    }
    public void onAccuracyChanged(Sensor s,int accuracy){}
    private void aggregate(String type,ArrayList<Float> values,String unit)throws Exception{
        JSONObject row=new JSONObject().put("type",type).put("unit",unit).put("samples",values.size());
        if(values.isEmpty()&&type.equals("light")&&lastLight!=null){row.put("value",lastLight).put("observation_mode","last_known_no_new_event").put("observed_at_epoch_ms",lightObserved);}
        else if(values.isEmpty())row.put("value",JSONObject.NULL).put("missing_reason","no_valid_events");else{Collections.sort(values);row.put("min",values.get(0)).put("max",values.get(values.size()-1)).put("value",values.get(values.size()/2));}write(row);values.clear();
    }
    void marker(long offset)throws Exception{write(new JSONObject().put("type","user_marker").put("offset_ms",offset));stream.getFD().sync();}
    private void write(JSONObject row)throws Exception{if(stream==null)throw new IOException("环境输出未开启");row.put("at_epoch_ms",System.currentTimeMillis()).put("elapsed_ms",SystemClock.elapsedRealtime());stream.write((row.toString()+"\n").getBytes("UTF-8"));reminder.observe(context,policy,row);}
    void finishOutput()throws Exception{
        if(stream==null)return;
        try{if(!pressure.isEmpty())aggregate("pressure",pressure,"hPa");if(policy.optBoolean("light"))aggregate("light",light,"lux");if(steps!=null)write(new JSONObject().put("type","steps_counter").put("value",steps).put("unit","steps_since_boot").put("observed_at_epoch_ms",stepsObserved));stream.getFD().sync();}finally{try{stream.close();}finally{stream=null;}}
        if(!pending.renameTo(complete))throw new IOException("环境记录保存失败");
    }
    void close()throws Exception{if(closed)return;closed=true;manager.unregisterListener(this);cancelCallbacks();finishHeart();finishOutput();}
}
