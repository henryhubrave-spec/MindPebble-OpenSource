package org.diy.liferecorder;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Five explicit content choices, saved through the system document picker. */
public final class ExportActivity extends AppScreen {
    private static final int SAVE_ZIP=701;
    private final ExecutorService exportWorker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final ArrayList<View> controls=new ArrayList<>();
    private final HashMap<Integer,CheckBox> checks=new HashMap<>();
    private long start,endDay;private int kinds=LocalExport.TEXT;private boolean workflow,busy;
    private String sourceId="",sourceTitle="";private LocalExport.Plan pending;
    private ProgressBar bar;private TextView status;private Button cancelButton,saveButton;
    private long lastProgress;

    public void onCreate(Bundle saved){
        super.onCreate(saved);Calendar day=Calendar.getInstance();day.setTimeInMillis(getIntent().getLongExtra("day",System.currentTimeMillis()));midnight(day);start=endDay=day.getTimeInMillis();workflow=getIntent().getBooleanExtra("workflow",false);sourceId=getIntent().getStringExtra("source");if(sourceId==null)sourceId="";
        if(saved!=null){start=saved.getLong("start",start);endDay=saved.getLong("end",endDay);kinds=saved.getInt("kinds",kinds);workflow=saved.getBoolean("workflow",workflow);}
        if(!sourceId.isEmpty())try{JSONObject source=store.audio(sourceId);day.setTimeInMillis(source.optLong("created"));midnight(day);start=endDay=day.getTimeInMillis();sourceTitle=source.optString("title");kinds&=~LocalExport.DAILY;}catch(Exception e){sourceTitle="记录已删除";}
        render();if(saved!=null&&saved.getBoolean("busy"))status.setText("上次导出已中断，请重新选择保存位置。");
    }
    private static void midnight(Calendar c){c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);}
    private long endExclusive(){Calendar c=Calendar.getInstance();c.setTimeInMillis(endDay);c.add(Calendar.DAY_OF_MONTH,1);return c.getTimeInMillis();}
    private String date(long time){return new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.CHINA).format(new Date(time));}
    private void render(){
        frame("导出与工作流");controls.clear();checks.clear();button(body,"‹ 返回",this::onBackPressed);
        body.addView(title(workflow?"接入你的工作流":"带走你的记录",26));body.addView(text(workflow?"挑选文字资料，交给电脑上的 Agent 继续整理。":"原音、转写和生活回顾，按需要保存。",14,MUTED));gap(body,18);
        LinearLayout range=card(body);range.addView(title(sourceId.isEmpty()?"选择日期":"当前录音",18));
        if(sourceId.isEmpty()){
            controls.add(button(range,"开始 · "+date(start),()->pickDate(true)));controls.add(button(range,"结束 · "+date(endDay),()->pickDate(false)));
            range.addView(text("按录音开始日期选择整段录音。每日回顾仅包含完整落在此范围内的日期。",12,MUTED));
        }else{range.addView(rawText(sourceTitle,15,INK));range.addView(text(date(start)+" · 只导出这条录音",12,SAGE));}
        LinearLayout content=card(body);content.addView(title("导出哪些内容",18));
        if(!workflow)addChoice(content,LocalExport.ORIGINAL,"原始录音","保留文件原格式；仅导出已同步且仍保留的原音。");
        addChoice(content,LocalExport.TRANSCRIPT,"带时间码的转写","TXT 与 JSON，保留来源、说话人与原始时间。");
        addChoice(content,LocalExport.HIGHLIGHTS,"标记重点","手动标记的时间与备注，保存为 Markdown 与 JSON。");
        addChoice(content,LocalExport.SUMMARY,"内容总结","录音与逐小时总结，保留原文引用和来源编号。");
        if(sourceId.isEmpty())addChoice(content,LocalExport.DAILY,"每日回顾","已生成且来源有效的日回顾，包含结论依据。");
        LinearLayout presets=row();controls.add(button(presets,"全部文字",()->setKinds(LocalExport.TEXT)));if(!workflow)controls.add(button(presets,"全选",()->setKinds(LocalExport.ALL)));controls.add(button(presets,"清空",()->setKinds(0)));content.addView(presets);
        LinearLayout info=card(body);info.addView(title(workflow?"电脑侧如何使用":"保存为通用 ZIP",17));info.addView(text(workflow?"保存后将 ZIP 复制到电脑，解压并把所需文件提供给 Agent。资料包内有阅读说明与来源索引。":"ZIP 内按内容类型分目录，附带来源索引和跳过清单，可用常见文本工具打开。",13,MUTED));info.addView(text(workflow?"只包含你选中的文字；原音默认不提供。不会自动连接电脑或上传资料。":"缺失的原音、范围外或已失效的整理结果会列明跳过原因。未生成的内容不创建文件。",12,SAGE));
        status=text("",13,MUTED);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(status);bar=progressBar(body);bar.setVisibility(View.GONE);
        saveButton=primary(body,workflow?"保存工作流资料包":"选择位置并导出",this::prepare);controls.add(saveButton);cancelButton=button(body,"取消导出",()->{cancelled.set(true);cancelButton.setEnabled(false);status.setText("正在取消并清理未完成的文件…");});cancelButton.setVisibility(View.GONE);
    }
    private void addChoice(LinearLayout parent,int flag,String title,String description){
        CheckBox check=new CheckBox(this);check.setText(AppLanguage.text(this,title));check.setTextSize(15);check.setTypeface(text("",15,INK).getTypeface());check.setTextColor(INK);check.setMinHeight(dp(48));check.setButtonTintList(ColorStateList.valueOf(ACCENT));check.setChecked((kinds&flag)!=0);check.setOnCheckedChangeListener((v,selected)->{if(selected)kinds|=flag;else kinds&=~flag;});parent.addView(check);TextView hint=text(description,12,MUTED);hint.setPadding(dp(32),0,0,dp(10));parent.addView(hint);checks.put(flag,check);controls.add(check);
    }
    private void setKinds(int flags){kinds=flags;if(workflow)kinds&=~LocalExport.ORIGINAL;if(!sourceId.isEmpty())kinds&=~LocalExport.DAILY;for(Map.Entry<Integer,CheckBox> entry:checks.entrySet())entry.getValue().setChecked((kinds&entry.getKey())!=0);}
    private void pickDate(boolean first){Calendar c=Calendar.getInstance();c.setTimeInMillis(first?start:endDay);new PebbleDatePickerDialog(this,(picker,y,m,d)->{c.set(y,m,d);midnight(c);if(first)start=c.getTimeInMillis();else endDay=c.getTimeInMillis();render();},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();}
    private void setBusy(boolean value){busy=value;for(View control:controls)control.setEnabled(!value);bar.setVisibility(value?View.VISIBLE:View.GONE);cancelButton.setVisibility(value?View.VISIBLE:View.GONE);cancelButton.setEnabled(value);}
    private void prepare(){
        final LocalExport.Options options;try{options=new LocalExport.Options(start,endExclusive(),kinds,workflow,sourceId);}catch(Exception e){status.setText(e.getMessage());return;}
        cancelled.set(false);setBusy(true);bar.setIndeterminate(true);status.setText("正在整理所选资料…");
        exportWorker.execute(()->{
            try(RecordStore db=new RecordStore(getApplicationContext())){
                LocalExport.Plan plan=LocalExport.prepare(db,options,cancelled::get);
                runOnUiThread(()->{if(isDestroyed())return;if(cancelled.get()){setBusy(false);status.setText("导出已取消");return;}pending=plan;status.setText(plan.describe()+"\n"+plan.sources.length()+" 条录音来源 · 约 "+android.text.format.Formatter.formatShortFileSize(this,plan.totalBytes)+(plan.warnings.isEmpty()?"":"\n"+plan.warnings.size()+" 项内容不可用，将在导出包中说明"));
                    if(!plan.warnings.isEmpty())new LocalDialog(this).setTitle("部分内容将跳过").setMessage(joinWarnings(plan.warnings)).setPositiveButton("继续保存",(d,w)->chooseDestination()).setNegativeButton("调整选择",(d,w)->{pending=null;setBusy(false);}).setOnCancelListener(d->{pending=null;setBusy(false);}).show();else chooseDestination();
                });
            }catch(Exception error){runOnUiThread(()->{if(!isDestroyed()){setBusy(false);status.setText(error instanceof LocalExport.Cancelled?"导出已取消":error.getMessage()==null?"读取资料失败，请重试。":error.getMessage());}});}
        });
    }
    private String joinWarnings(List<String> warnings){StringBuilder out=new StringBuilder("其余可用内容仍会保存。\n");for(int i=0;i<Math.min(4,warnings.size());i++)out.append('\n').append(warnings.get(i));if(warnings.size()>4)out.append("\n\n另有 ").append(warnings.size()-4).append(" 项，详见导出包 manifest.json。");return out.toString();}
    private void chooseDestination(){
        if(cancelled.get()||pending==null){setBusy(false);status.setText("导出已取消");return;}
        try{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,(workflow?"MindPebble-workflow-":"MindPebble-export-")+date(start)+".zip");startActivityForResult(intent,SAVE_ZIP);}catch(Exception e){pending=null;setBusy(false);status.setText("无法打开系统文件选择器，请确认手机已启用文件应用后重试。");}
    }
    protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=SAVE_ZIP)return;
        if(result!=RESULT_OK||data==null||data.getData()==null){pending=null;setBusy(false);status.setText("未保存，可重新选择导出位置。");return;}
        Uri destination=data.getData();LocalExport.Plan plan=pending;pending=null;
        if(plan==null){exportWorker.execute(()->cleanup(destination));setBusy(false);status.setText("页面已重建，请重新导出；未完成的文件已请求清理。");return;}
        setBusy(true);bar.setIndeterminate(false);status.setText("正在写入 ZIP…");lastProgress=0;
        exportWorker.execute(()->{
            try{OutputStream output=getContentResolver().openOutputStream(destination,"wt");if(output==null)throw new IOException("保存位置无法写入，请换一个文件夹。");
                try(OutputStream stream=output){LocalExport.writeSelected(plan,stream,(label,done,total)->{long now=android.os.SystemClock.elapsedRealtime();if(now-lastProgress<150&&done<total)return;lastProgress=now;runOnUiThread(()->{if(!isDestroyed()){progress(bar,done,total);status.setText("正在导出 · "+(total==0?0:Math.min(100,done*100/total))+"%\n"+label);}});},cancelled::get);}
                runOnUiThread(()->{if(!isDestroyed()){setBusy(false);status.setText("已保存 ZIP · "+plan.contentFiles+" 个文件\n"+plan.describe()+(plan.warnings.isEmpty()?"":"\n已跳过 "+plan.warnings.size()+" 项，原因保存在 manifest.json。"));saveButton.setText(AppLanguage.text(this,"再次导出"));}});
            }catch(Exception error){boolean removed=cleanup(destination);String reason=error instanceof LocalExport.Cancelled?"导出已取消。":error instanceof IOException?"保存失败："+(error.getMessage()==null?"请检查剩余空间与保存权限。":error.getMessage()):"导出失败，请重新选择位置并重试。";runOnUiThread(()->{if(!isDestroyed()){setBusy(false);status.setText(reason+(removed?"\n未完成的文件已清理。":"\n此位置不允许清理，请在文件应用中删除未完成的 ZIP。"));saveButton.setText(AppLanguage.text(this,"重试导出"));}});}
        });
    }
    private boolean cleanup(Uri uri){try{if(DocumentsContract.isDocumentUri(this,uri)&&DocumentsContract.deleteDocument(getContentResolver(),uri))return true;}catch(Exception ignored){}try(OutputStream output=getContentResolver().openOutputStream(uri,"wt")){if(output!=null)output.flush();}catch(Exception ignored){}return false;}
    protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putLong("start",start);state.putLong("end",endDay);state.putInt("kinds",kinds);state.putBoolean("workflow",workflow);state.putBoolean("busy",busy);}
    @Override public void onBackPressed(){if(!busy){finish();return;}new LocalDialog(this).setTitle("取消本次导出？").setMessage("已保存的原始资料不会改变。未完成的导出文件会尝试清理。").setPositiveButton("取消并返回",(d,w)->{cancelled.set(true);finish();}).setNegativeButton("继续导出",null).show();}
    @Override public void onDestroy(){cancelled.set(true);exportWorker.shutdown();super.onDestroy();}
}
