package org.diy.liferecorder;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;import android.view.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** User-owned model downloads, activation and local voice enrollment. */
public final class OfflineActivity extends AppScreen {
    private OfflinePacks packs;private String section="";private final Map<String,ProgressBar> progressBars=new HashMap<>();
    private final Handler refresh=new Handler();
    private final Map<String,TextView> states=new HashMap<>();
    private String lastActive="";
    private final Runnable tick=new Runnable(){public void run(){try{for(String id:states.keySet()){setLabel(states.get(id),packs.state(id));ProgressBar bar=progressBars.get(id);if(bar!=null){boolean active=ModelDownloadService.active.equals(id);bar.setVisibility(active||packs.ready(id)?View.VISIBLE:View.GONE);progress(bar,active?ModelDownloadService.downloadDone:1,active?ModelDownloadService.downloadTotal:1);}}if(!lastActive.equals(ModelDownloadService.active)){lastActive=ModelDownloadService.active;render();}}catch(Exception ignored){}refresh.postDelayed(this,1000);}};
    public void onCreate(Bundle b){super.onCreate(b);section=getIntent().getStringExtra("section")==null?"":getIntent().getStringExtra("section");try{packs=new OfflinePacks(this);render();}catch(Exception e){frame("离线资源");notice.setText(AppLanguage.text(this,"资源目录读取失败，请保留数据重试"));}}
    public void onResume(){super.onResume();refresh.post(tick);}
    public void onPause(){refresh.removeCallbacks(tick);super.onPause();}
    private void render(){try{
        states.clear();progressBars.clear();frame("离线能力，按需准备");button(body,"‹ 返回高级设置",this::finish);body.addView(title(section.equals("voice")?"声纹管理":section.equals("scheduling")?"自动离线整理":"离线语音资源",25));if(section.isEmpty()){home();return;}
        if(section.equals("packs")){LinearLayout info=card(body);info.addView(title("适合这台手机的方案",19));info.addView(text(packs.deviceSummary(),13,MUTED));
        info.addView(text("初步推荐："+(packs.recommended().equals("standard")?"标准语音包":"轻量语音包")+"。推荐结合内存、短测及持续任务耗时；下载后在本机测试，实际续航需持续使用验证。",13,MUTED));
        info.addView(text("只下载你选择的资源。下载会访问所示公开资源主机，不发送录音；下载后转写与声纹匹配在本机执行。",12,MUTED));
        Switch mirror=new Switch(this);mirror.setText(AppLanguage.text(this,"使用国内下载镜像（hf-mirror.com）"));mirror.setMinHeight(dp(52));mirror.setChecked(packs.useMirror());info.addView(mirror);mirror.setOnCheckedChangeListener((b,on)->{packs.set("mirror",on);});info.addView(text("原站为 huggingface.co，镜像仅提供模型文件下载；两者使用同一固定 SHA-256 校验。切换源不会上传录音。",12,MUTED));
        JSONArray catalog=packs.all();
        for(int i=0;i<catalog.length();i++){
            JSONObject p=catalog.getJSONObject(i);String id=p.getString("id");LinearLayout c=card(body);
            c.addView(title(p.getString("name"),19));c.addView(text(p.getString("description"),13,MUTED));
            c.addView(text(String.format(Locale.CHINA,"下载 %.1f MB · %s",packs.bytes(id)/1048576.0,p.optString("languages")),12,MUTED));
            TextView state=text(packs.state(id),13,ACCENT);states.put(id,state);c.addView(state);ProgressBar download=progressBar(c);progressBars.put(id,download);boolean downloading=ModelDownloadService.active.equals(id);download.setVisibility(downloading||packs.ready(id)?View.VISIBLE:View.GONE);progress(download,downloading?ModelDownloadService.downloadDone:1,downloading?ModelDownloadService.downloadTotal:1);
            String benchmark=packs.prefs.getString(id+"-benchmark","");if(!benchmark.isEmpty()){JSONObject m=new JSONObject(benchmark);c.addView(text(String.format(Locale.CHINA,"本机短测：加载 %.2f 秒 · 处理 %.2f 秒 / %.2f 秒语音\n推理后参考内存 %.0f MB",m.optDouble("load_ms")/1000,m.optDouble("inference_ms")/1000,m.optDouble("audio_ms")/1000,m.optDouble("pss_kb")/1024),12,MUTED));}
            if(ModelDownloadService.active.equals(id))button(c,"暂停下载 / 取消验证",()->{startService(new Intent(this,ModelDownloadService.class).setAction("pause"));notice.setText(AppLanguage.text(this,"正在暂停，已下载部分保留"));});
            else{
                button(c,packs.installed(id)?"重新进行本机验证":"下载 / 继续下载",()->download(id));
                if(!id.equals("speaker")&&packs.ready(id))button(c,packs.selected().equals(id)?"当前已选方案":"使用这个方案（新任务）",()->{try{packs.select(id);OfflineScheduler.schedule(this);render();}catch(Exception e){notice.setText(e.getMessage());}});
                button(c,"删除这个资源包",()->new LocalDialog(this).setTitle("释放模型空间？").setMessage("录音、已有转写和声纹资料继续保留。删除当前语音包会关闭自动转写，可重新下载启用。").setPositiveButton("删除",(d,w)->background(()->packs.remove(id),this::render)).setNegativeButton("取消",null).show());
            }
            button(c,"查看来源与许可",()->message(p.optString("name"),p.optString("source")+"\n\n"+p.optString("license")+"\n\n"+p.optString("license_url")));
        }
        }
        if(section.equals("scheduling")){LinearLayout scheduling=card(body);scheduling.addView(title("自动整理",19));
        Switch automatic=new Switch(this);automatic.setText(AppLanguage.text(this,"自动离线转写新收到的录音"));automatic.setMinHeight(dp(52));automatic.setChecked(packs.automatic());scheduling.addView(automatic);
        automatic.setOnCheckedChangeListener((b,on)->{try{if(on&&(packs.selected().isEmpty()||!packs.ready(packs.selected()))){b.setChecked(false);notice.setText(AppLanguage.text(this,"请先下载、验证并选择语音包"));return;}packs.set("automatic",on);if(on)getSharedPreferences("offline-scheduling",0).edit().putBoolean("paused",false).apply();if(on)OfflineScheduler.enqueueMissing(this);else OfflineScheduler.cancelAutomatic(this);OfflineScheduler.schedule(this);}catch(Exception e){notice.setText(e.getMessage());}});
        Switch charge=new Switch(this);charge.setText(AppLanguage.text(this,"仅充电时自动整理"));charge.setMinHeight(dp(52));charge.setChecked(packs.chargingOnly());scheduling.addView(charge);charge.setOnCheckedChangeListener((b,on)->{packs.set("charging-only",on);OfflineScheduler.schedule(this);});
        scheduling.addView(text("低电量、内存紧张或明显发热时自动延后。录音继续保存，稍后补齐；自动整理只使用本地模型。",12,MUTED));
        button(scheduling,"补处理尚未转写的历史录音",()->{if(!packs.automatic()){notice.setText(AppLanguage.text(this,"请先选择语音包并开启自动整理"));return;}background(()->OfflineScheduler.enqueueMissing(this),()->{OfflineScheduler.schedule(this);notice.setText(AppLanguage.text(this,"已加入离线队列，按上述条件处理"));});});
        button(scheduling,"立即处理已排队任务",()->{try{OfflineScheduler.runNow(this,"");startActivity(new Intent(this,TaskCenterActivity.class));}catch(Exception e){notice.setText(e.getMessage());}});button(scheduling,"查看每项处理进度",()->startActivity(new Intent(this,TaskCenterActivity.class)));}
        if(section.equals("voice")){VoiceManager.show(this,body,this::render);LinearLayout voice=card(body);voice.addView(title("让记录认出你的声音",19));
        voice.addView(text(packs.prefs.contains("my-voice")?"已保存本人声纹 · 仅用于本机匹配":"尚未录入本人声纹",13,MUTED));
        voice.addView(text("选择一段只有你说话的 5–30 秒清晰录音。声纹使用 Android Keystore 加密保存；多人混说、轻声和噪声可能无法可靠匹配。",12,MUTED));
        voice.addView(text("手机与手表麦克风可能存在差异。可在手表录音详情回听后，用清晰的本人单人语句补充声纹；再点击重新匹配声纹，无需重做转写。自动标签仅供核对，不自动学习本人身份。",12,MUTED));
        primary(voice,"直接录制本人声音",()->{try{if(!packs.ready("speaker")){notice.setText(AppLanguage.text(this,"请先下载并验证声纹识别包"));return;}startActivityForResult(new Intent(this,VoiceEnrollmentActivity.class),45);}catch(Exception e){notice.setText(e.getMessage());}});
        button(voice,"选择本人声音样本",()->{try{if(!packs.ready("speaker")){notice.setText(AppLanguage.text(this,"请先下载并验证声纹识别包"));return;}startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE),43);}catch(Exception e){notice.setText(e.getMessage());}});
        button(voice,"删除本人声纹",()->new LocalDialog(this).setMessage("删除用于未来匹配的本人声纹；历史标注保留，可在记录详情纠正。").setPositiveButton("删除",(d,w)->{try{VoiceProfiles.remove(packs,VoiceProfiles.SELF,null);render();}catch(Exception e){notice.setText(e.getMessage());}}).setNegativeButton("取消",null).show());
        }button(body,"刷新资源状态",this::render);
    }catch(Exception e){notice.setText(AppLanguage.text(this,"资源状态读取失败，请重试"));}}
    private void home(){LinearLayout menu=card(body);button(menu,"语音包下载与验证  ›",()->startActivity(new Intent(this,OfflineActivity.class).putExtra("section","packs")));button(menu,"自动整理与进度  ›",()->startActivity(new Intent(this,OfflineActivity.class).putExtra("section","scheduling")));button(menu,"本人声音与声纹  ›",()->startActivity(new Intent(this,OfflineActivity.class).putExtra("section","voice")));button(body,"打开任务中心",()->startActivity(new Intent(this,TaskCenterActivity.class)));}
    private void download(String id){try{
        if(!ModelDownloadService.active.isEmpty()){notice.setText(AppLanguage.text(this,"请等待当前资源完成，或先暂停"));return;}
        String text=packs.installed(id)?"使用资源包内的公开测试音频，在本机验证加载与推理。":"下载源："+new java.net.URI(packs.downloadUrl(packs.pack(id).getJSONArray("files").getJSONObject(0).getString("url"))).getHost()+String.format(Locale.CHINA,"\n下载约 %.1f MB，需预留额外 128 MB。建议使用 Wi-Fi。",packs.bytes(id)/1048576.0)+"\n下载完成后自动进行本机运行验证。";
        new LocalDialog(this).setTitle("准备离线资源").setMessage(text).setPositiveButton("开始",(d,w)->{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},44);startForegroundService(new Intent(this,ModelDownloadService.class).putExtra("pack",id));lastActive=id;refresh.postDelayed(this::render,500);}).setNegativeButton("取消",null).show();
    }catch(Exception e){notice.setText(e.getMessage());}}
    protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==45){if(result==RESULT_OK)render();return;}if(request!=43||result!=RESULT_OK||data==null||data.getData()==null)return;
        final android.net.Uri uri=data.getData();new LocalDialog(this).setTitle("确认这是你本人的声音").setMessage("样本应只有你一个人说话。仅在本机生成声纹，不上传、不加入录音库；临时样本处理后删除。").setPositiveButton("确认并录入",(d,w)->background(()->{
            File tmp=File.createTempFile("voice-enrollment-",".audio",getCacheDir());try{try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(tmp)){if(in==null)throw new IOException("无法读取样本");byte[] b=new byte[8192];int n;long total=0;while((n=in.read(b))!=-1){total+=n;if(total>16*1024*1024)throw new IOException("样本过大，请使用 5–30 秒录音");out.write(b,0,n);}}
                OfflineSpeech.enroll(packs,tmp);
            }finally{tmp.delete();}
        },this::render)).setNegativeButton("取消",null).show();
    }
}
