package org.diy.watchrecorder;

import android.app.*;
import android.bluetooth.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.widget.*;
import android.graphics.Color;
import java.util.*;
import org.diy.lifecore.BluetoothTransport;

public final class LinkActivity extends Activity {
    private TextView status,selected;
    private EditText code;
    private String address="";
    public void onCreate(Bundle b){super.onCreate(b);ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(35,25,35,35);box.setBackgroundColor(Color.BLACK);scroll.addView(box);setContentView(scroll);
        add(box,"‹ 返回记录",this::finish);
        TextView title=new TextView(this){public void setText(CharSequence value,TextView.BufferType type){super.setText(WatchLanguage.text(getContext(),value),type);}};title.setText("蓝牙连接我的手机");title.setTextSize(17);box.addView(title);
        TextView instructions=new TextView(this){public void setText(CharSequence value,TextView.BufferType type){super.setText(WatchLanguage.text(getContext(),value),type);}};instructions.setText("两端打开系统蓝牙，手机 App 开启蓝牙接收并生成配对码。不用 Wi-Fi 或热点。自动约每30分钟批量同步，也可立即手动同步。");instructions.setTextSize(12);box.addView(instructions);
        address=WatchLink.prefs(this).getString("bluetooth_address","");
        selected=new TextView(this){public void setText(CharSequence value,TextView.BufferType type){super.setText(WatchLanguage.text(getContext(),value),type);}};selected.setText(address.isEmpty()?"尚未选择手机":"已选手机："+address);selected.setTextSize(12);box.addView(selected);
        add(box,"选择已配对的手机",this::choosePhone);
        code=new EditText(this);code.setHint(WatchLanguage.text(this,"手机的 8 位配对码"));code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);code.setSingleLine(true);code.setSaveEnabled(false);box.addView(code);
        add(box,"通过蓝牙配对 App",()->{
            final String target=address,entered=code.getText().toString();
            if(target.isEmpty()){status.setText("请先选择已配对手机");return;}
            if(!entered.matches("[0-9]{8}")){status.setText("请填写手机当前 8 位码");return;}
            background(()->{WatchLink.pair(this,target,entered);WatchSyncJob.schedule(this);WatchLink.prefs(this).edit().putString("status","蓝牙已配对，等待同步").commit();},"App 已配对，可以同步");
        });
        add(box,"立即蓝牙同步 / 更新设置",()->background(()->WatchLink.sync(this),"同步完成，原件保留"));
        add(box,"允许可选心率读取",()->{if(checkSelfPermission(android.Manifest.permission.BODY_SENSORS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.BODY_SENSORS},18);else status.setText("心率已授权；还需在手机开启采样");});
        add(box,"清理已到手机的手表副本",()->new WatchDialog(this).setTitle("仅剩手机副本")
          .setMessage("将通过蓝牙重新核对，再清理手机已确认保存的片段。未同步、手机已遗忘和临时录音会保留。如果手机只留语音，清理后静音区间将无法恢复。")
          .setPositiveButton("核对并清理",(dialog,which)->background(()->{
              getSharedPreferences("phone-receipts",0).edit().clear().commit();WatchLink.sync(this);
              SharedPreferences receipts=getSharedPreferences("phone-receipts",0);java.io.File dir=new java.io.File(getFilesDir(),"recordings");java.io.File[] files=dir.listFiles((d,n)->n.endsWith(".m4a")||n.endsWith(".context.jsonl"));
              if(files!=null)for(java.io.File file:files)if((receipts.getString(file.getName()+".state","").equals("DURABLE")||receipts.getString(file.getName()+".state","").equals("SPEECH_ONLY"))&&org.diy.lifecore.LocalSecurity.hash(file).equals(receipts.getString(file.getName(),""))){if(!file.delete())throw new java.io.IOException("部分副本无法清理");FinalizedDigest.forget(this,file);new java.io.File(file.getPath()+".json").delete();}
          },"已清理确认到手机的副本")) .setNegativeButton("取消",null).show());
        add(box,"解除 App 配对",()->new WatchDialog(this).setMessage("停止向该手机同步，不删除录音，也不解除系统蓝牙配对。")
          .setPositiveButton("解除",(d,w)->{getSystemService(android.app.job.JobScheduler.class).cancel(8267);WatchLink.prefs(this).edit().remove("token").remove("bluetooth_address").putString("status","未配对").commit();status.setText("未配对");}).setNegativeButton("取消",null).show());
        status=new TextView(this){public void setText(CharSequence value,TextView.BufferType type){super.setText(WatchLanguage.text(getContext(),value),type);}};status.setText(WatchLink.paired(this)?WatchLink.prefs(this).getString("status","蓝牙已配对"):"请重新完成 App 蓝牙配对");status.setTextSize(12);box.addView(status);
    }
    private void choosePhone(){try{
        List<BluetoothDevice> devices=BluetoothTransport.bonded(this);
        if(devices.isEmpty()){status.setText("未找到系统已配对设备，请先用 Wear OS 完成手机配对");return;}
        String[] names=new String[devices.size()];for(int i=0;i<names.length;i++){BluetoothDevice d=devices.get(i);names[i]=(d.getName()==null?"已配对设备":d.getName())+"\n"+d.getAddress();}
        new WatchDialog(this).setTitle("选择自己的手机").setItems(names,(dialog,which)->{BluetoothDevice device=devices.get(which);address=device.getAddress();selected.setText((device.getName()==null?"已选手机":device.getName())+"\n"+address);}).setNegativeButton("取消",null).show();
    }catch(Exception e){status.setText(e instanceof java.io.IOException?e.getMessage():"请检查系统蓝牙配对");}}
    private void add(LinearLayout parent,String label,Runnable action){Button b=new Button(this){public void setText(CharSequence value,TextView.BufferType type){super.setText(WatchLanguage.text(getContext(),value),type);}};b.setText(label);b.setTextSize(12);parent.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->action.run());}
    interface Task{void run()throws Exception;}
    private void background(Task task,String success){status.setText("正在通过蓝牙处理…");new Thread(()->{try{task.run();runOnUiThread(()->status.setText(success));}catch(Exception e){runOnUiThread(()->status.setText("未完成："+(e instanceof java.io.IOException?e.getMessage():"请检查系统蓝牙和手机接收权限")));}},"watch-bluetooth-link").start();}
}
