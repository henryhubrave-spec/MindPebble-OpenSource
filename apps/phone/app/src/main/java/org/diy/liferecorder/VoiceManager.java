package org.diy.liferecorder;
import android.content.*;import android.widget.*;import org.json.*;
final class VoiceManager {
 static void show(AppScreen screen,LinearLayout body,Runnable refresh)throws Exception{
  OfflinePacks packs=new OfflinePacks(screen);body.addView(screen.text("管理本人和其他说话人的声纹，可从完整原文点击每句说话人登记或补充。自动匹配显示为候选；历史人工标注保留。",13,screen.MUTED));
  screen.button(body,"新建说话人",()->screen.editText("说话人名称","",name->{try{VoiceProfiles.create(packs,name);refresh.run();}catch(Exception e){screen.notice.setText(e.getMessage());}}));
  JSONArray all=VoiceProfiles.read(packs);for(int i=0;i<all.length();i++){JSONObject person=all.getJSONObject(i);String id=person.getString("id");LinearLayout card=screen.card(body);card.addView(screen.rawTitle(person.getString("name")+(id.equals(VoiceProfiles.SELF)?" · 本人":""),18));JSONArray samples=person.getJSONArray("samples");card.addView(screen.text(samples.length()+" 个样本 · "+(!person.optBoolean("enabled",true)?"已停用":samples.length()==0?"待登记声音":"参与候选匹配"),12,screen.SAGE));
   screen.button(card,"修改名称",()->screen.editText("声纹名称",person.optString("name"),name->{try{VoiceProfiles.change(packs,id,"name",name.trim());refresh.run();}catch(Exception e){screen.notice.setText(e.getMessage());}}));
   screen.button(card,person.optBoolean("enabled",true)?"停用匹配":"启用匹配",()->{try{VoiceProfiles.change(packs,id,"enabled",!person.optBoolean("enabled",true));refresh.run();}catch(Exception e){screen.notice.setText(e.getMessage());}});
   for(int n=0;n<samples.length();n++){JSONObject sample=samples.getJSONObject(n);LinearLayout row=screen.inset(card,screen.TINT);String source=sample.optString("source_id");row.addView(screen.rawText(source.isEmpty()?(sample.optString("origin").equals("legacy")?"原有声纹样本（已迁移）":"直接录入的样本"):sample.optString("source_title")+" · "+sample.optLong("start_ms")/1000+"–"+sample.optLong("end_ms")/1000+" 秒",13,screen.INK));if(!sample.getJSONObject("profile").optString("model_version").equals(packs.pack("speaker").optString("version")))row.addView(screen.text("模型版本已更新，此样本暂不参与匹配，可从来源重新登记。",12,screen.ACCENT));row.addView(screen.text(new java.text.SimpleDateFormat("M/d HH:mm",java.util.Locale.CHINA).format(new java.util.Date(sample.optLong("created")))+" · 模型 "+sample.getJSONObject("profile").optString("model_version"),11,screen.MUTED));
    if(!source.isEmpty())screen.button(row,"回听来源样本",()->{try{screen.store.audio(source);screen.startActivity(new Intent(screen,RecordActivity.class).putExtra("id",source).putExtra("seek_ms",sample.optLong("start_ms")));}catch(Exception e){screen.notice.setText("来源录音已删除，提取的声纹仍可单独移除");}});
    screen.button(row,"移除这个样本",()->new LocalDialog(screen).setMessage("移除该声纹样本，重新计算后续匹配使用的声纹。").setPositiveButton("移除",(d,w)->{try{VoiceProfiles.remove(packs,id,sample.optString("id"));refresh.run();}catch(Exception e){screen.notice.setText(e.getMessage());}}).setNegativeButton("取消",null).show());
   }
   screen.button(card,"删除这位说话人的声纹",()->new LocalDialog(screen).setMessage("删除声纹和样本登记，保留原录音与历史人工标注。").setPositiveButton("删除",(d,w)->{try{VoiceProfiles.remove(packs,id,null);refresh.run();}catch(Exception e){screen.notice.setText(e.getMessage());}}).setNegativeButton("取消",null).show());
  }
 }
}
