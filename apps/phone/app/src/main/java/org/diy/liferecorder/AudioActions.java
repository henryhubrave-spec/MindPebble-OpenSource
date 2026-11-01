package org.diy.liferecorder;
import android.app.*;import org.json.*;
final class AudioActions {
 static void show(AppScreen screen,String source,Runnable done){new LocalDialog(screen).setTitle("删除与存储位置").setItems(new String[]{"仅删除手机音频，保留转写与待办","仅删除手表原始片段","删除手机整条记录","同时删除手机整条记录与手表原件"},(d,index)->{new LocalDialog(screen).setTitle("确认删除？").setMessage(index==0?"删除手机内可播放的音频文件，保留转写、待办与来源时间。":index==1?"发送删除指令到已配对手表，下次蓝牙同步后显示结果。":index==2?"删除手机音频、原文及关联待办。手表原件保留，手机不会再次导入此片段。":"删除手机音频、原文及关联待办，并将手表原件加入删除队列。手表操作在下次同步后生效。").setPositiveButton("删除",(dialog,which)->screen.background(()->{if(index==1||index==3)WatchArchive.request(screen.store,source);if(index==0)screen.store.releaseAudio(source);if(index==2||index==3)screen.store.forget(source);},()->{screen.notice.setText(index==1||index==3?"已提交，等待手表同步确认":"已删除");done.run();})).setNegativeButton("取消",null).show();}).show();}
}
