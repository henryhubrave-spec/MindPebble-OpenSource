package org.diy.liferecorder;

import android.app.*;import android.content.*;import org.json.*;
public final class ReminderReceiver extends BroadcastReceiver {
    static void schedule(Context c,String id,long due){Intent i=new Intent(c,ReminderReceiver.class).setAction("task:"+id).putExtra("id",id);PendingIntent p=PendingIntent.getBroadcast(c,id.hashCode(),i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,due,p);}
    public void onReceive(Context c,Intent i){String id=i.getStringExtra("id");if(id==null)return;try(RecordStore db=new RecordStore(c)){JSONArray a=db.rows("SELECT * FROM tasks WHERE id=? AND state='accepted'",id);if(a.length()==0)return;JSONObject task=a.getJSONObject(0);NotificationManager n=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);n.createNotificationChannel(new NotificationChannel("task-reminders","本地待办提醒",NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent p=PendingIntent.getActivity(c,id.hashCode(),task.isNull("source")?new Intent(c,MainActivity.class).putExtra("page","tasks"):new Intent(c,RecordActivity.class).putExtra("id",task.getString("source")),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        n.notify(id.hashCode(),new Notification.Builder(c,"task-reminders").setSmallIcon(R.drawable.ic_record).setContentTitle(AppLanguage.text(c,"有一件事，记得回看")).setContentText(task.getString("text")).setContentIntent(p).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());
    }catch(Exception ignored){}}
}
