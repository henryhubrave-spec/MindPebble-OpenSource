package org.diy.liferecorder;

import android.app.*;
import android.os.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

/** Shared native visual primitives; domain and IO stay outside screens. */
abstract class AppScreen extends Activity {
    final int PAPER=Color.rgb(250,247,245),INK=Color.rgb(52,48,45),MUTED=Color.rgb(110,104,99),TINT=Color.rgb(245,242,239),ACCENT=Color.rgb(176,114,97),SAGE=Color.rgb(70,100,93),LINE=Color.rgb(232,226,220),MINT=Color.rgb(224,237,231);
    LinearLayout root,body;ScrollView scroll;TextView notice;RecordStore store;private Typeface regularFace,strongFace;private final Handler live=new Handler();private LinearLayout livePanel;private TextView liveText;private ProgressBar liveBar;private TextView taskEntry;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    protected void attachBaseContext(android.content.Context base){super.attachBaseContext(AppLanguage.wrap(base));}
    public void onCreate(Bundle b){super.onCreate(b);store=new RecordStore(this);try{regularFace=new Typeface.Builder(getAssets(),"design/PlusJakartaSans.ttf").setWeight(400).setFontVariationSettings("'wght' 400").build();strongFace=new Typeface.Builder(getAssets(),"design/PlusJakartaSans.ttf").setWeight(600).setFontVariationSettings("'wght' 600").build();}catch(Exception ignored){}}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    TextView text(String value,int size,int color){TextView t=new TextView(this){public void setText(CharSequence value,TextView.BufferType type){super.setText(AppLanguage.text(getContext(),value),type);}};t.setText(value);if(regularFace!=null)t.setTypeface(regularFace);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setPadding(0,dp(3),0,dp(3));t.setLineSpacing(dp(3),1);return t;}
    void setLabel(TextView target,String value){if(!android.text.TextUtils.equals(target.getText(),AppLanguage.text(this,value)))target.setText(value);}
    TextView rawText(String value,int size,int color){TextView t=new TextView(this);t.setText(value);if(regularFace!=null)t.setTypeface(regularFace);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setPadding(0,dp(3),0,dp(3));t.setLineSpacing(dp(3),1);return t;}
    TextView rawTitle(String value,int size){TextView t=rawText(value,size,INK);t.setTypeface(strongFace==null?Typeface.create("sans-serif",Typeface.BOLD):strongFace);return t;}
    TextView title(String value,int size){TextView t=text(value,size,INK);t.setTypeface(strongFace==null?Typeface.create("sans-serif",Typeface.BOLD):strongFace);return t;}
    void frame(String heading){
        root=column();root.setBackgroundColor(PAPER);setContentView(root);
        LinearLayout header=row();header.setPadding(dp(20),dp(10),dp(20),dp(10));header.setBackgroundColor(Color.rgb(253,252,251));
        android.widget.ImageView logo=new android.widget.ImageView(this);logo.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);logo.setContentDescription("MindPebble");header.addView(logo,new LinearLayout.LayoutParams(dp(34),dp(34)));
        LinearLayout brand=column();brand.setPadding(dp(10),0,0,0);TextView name=title("MindPebble",21);name.setPadding(0,0,0,0);brand.addView(name);
        TextView state=text(TransferProgress.current.active?"● 正在接收手表录音":SyncService.running?"● 已开启蓝牙接收":"● 本地记录",11,SAGE);state.setPadding(0,dp(1),0,0);brand.addView(state);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        taskEntry=text("任务",12,SAGE);taskEntry.setSingleLine(true);taskEntry.setTextSize(11);taskEntry.setGravity(Gravity.CENTER);taskEntry.setPadding(dp(8),dp(8),dp(8),dp(8));taskEntry.setBackground(bg(MINT,16));header.addView(taskEntry,new LinearLayout.LayoutParams(dp(70),dp(44)));taskEntry.setContentDescription(AppLanguage.text(this,"任务中心"));taskEntry.setOnClickListener(v->{if(!(this instanceof TaskCenterActivity))startActivity(new android.content.Intent(this,TaskCenterActivity.class));});

        root.addView(header);View line=new View(this);line.setBackgroundColor(LINE);root.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        livePanel=column();livePanel.setPadding(dp(20),dp(8),dp(20),dp(8));livePanel.setBackgroundColor(TINT);liveText=text("",11,SAGE);livePanel.addView(liveText);liveBar=progressBar(livePanel);livePanel.setVisibility(View.GONE);root.addView(livePanel);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));body=column();body.setPadding(dp(20),dp(20),dp(20),dp(28));android.widget.FrameLayout.LayoutParams contentSize=new android.widget.FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels,dp(760)),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);scroll.addView(body,contentSize);
        notice=text("",12,ACCENT);notice.setPadding(0,0,0,0);notice.setVisibility(View.GONE);notice.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){notice.setVisibility(s.length()==0?View.GONE:View.VISIBLE);}public void afterTextChanged(android.text.Editable e){}});notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(notice);
    }
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    void gap(LinearLayout parent,int h){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    android.widget.ImageView asset(String name){android.widget.ImageView v=new android.widget.ImageView(this);try(java.io.InputStream in=getAssets().open("design/"+name)){v.setImageDrawable(android.graphics.drawable.Drawable.createFromStream(in,name));}catch(Exception ignored){}v.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);return v;}
    LinearLayout card(LinearLayout parent){LinearLayout c=column();c.setPadding(dp(20),dp(18),dp(20),dp(18));GradientDrawable surface=bg(Color.WHITE,22);surface.setStroke(dp(1),LINE);c.setBackground(surface);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(18);parent.addView(c,p);return c;}
    LinearLayout inset(LinearLayout parent,int color){LinearLayout c=column();c.setPadding(dp(14),dp(12),dp(14),dp(12));c.setBackground(bg(color,14));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);parent.addView(c,p);return c;}
    TextView pill(LinearLayout parent,String label,int color,int fill){TextView t=text(label,11,color);t.setPadding(dp(10),dp(5),dp(10),dp(5));t.setBackground(bg(fill,30));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.rightMargin=dp(6);p.bottomMargin=dp(6);parent.addView(t,p);return t;}
    void section(String icon,String name,String action,Runnable click){LinearLayout r=row();PebbleIcon i=new PebbleIcon(this,icon,SAGE);r.addView(i,new LinearLayout.LayoutParams(dp(22),dp(22)));TextView h=title(name,19);h.setPadding(dp(8),dp(8),0,dp(12));r.addView(h,new LinearLayout.LayoutParams(0,-2,1));if(!action.isEmpty()){TextView a=text(action,11,ACCENT);a.setMinHeight(dp(48));a.setGravity(Gravity.CENTER);r.addView(a);a.setOnClickListener(v->click.run());}body.addView(r);}
    void nav(String selected,java.util.function.Consumer<String> change){LinearLayout nav=row();nav.setPadding(dp(12),dp(7),dp(12),dp(8));nav.setBackgroundColor(Color.rgb(253,252,251));String[] ids={"today","memory","tasks","settings"},names={"今日时光","语音回看","生活待办","高级设置"},icons={"sun","wave","check","tune"};for(int n=0;n<ids.length;n++){final String id=ids[n];int color=selected.equals(id)?ACCENT:MUTED;LinearLayout item=column();item.setGravity(Gravity.CENTER);PebbleIcon icon=new PebbleIcon(this,icons[n],color);item.addView(icon,new LinearLayout.LayoutParams(dp(25),dp(25)));TextView label=text(names[n],11,color);label.setPadding(0,dp(3),0,0);label.setGravity(Gravity.CENTER);item.addView(label);item.setContentDescription(AppLanguage.text(this,names[n]));nav.addView(item,new LinearLayout.LayoutParams(0,dp(54),1));item.setOnClickListener(v->change.accept(id));}View line=new View(this);line.setBackgroundColor(LINE);root.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));root.addView(nav);}
    android.graphics.drawable.StateListDrawable buttonSurface(int normal,int pressed,int disabled,int radius){android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();states.addState(new int[]{-android.R.attr.state_enabled},bg(disabled,radius));states.addState(new int[]{android.R.attr.state_pressed},bg(pressed,radius));states.addState(new int[]{},bg(normal,radius));return states;}
    Button button(LinearLayout parent,String label,Runnable action){Button b=new Button(this){public void setText(CharSequence value,TextView.BufferType type){super.setText(AppLanguage.text(getContext(),value),type);}};b.setText(label);b.setTextSize(13);b.setAllCaps(false);b.setElevation(0);b.setStateListAnimator(null);b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{Color.rgb(177,166,158),ACCENT}));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setBackground(buttonSurface(TINT,Color.rgb(245,230,218),PAPER,24));b.setPadding(dp(12),dp(8),dp(12),dp(8));PebbleSurfaces.control(b,false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.gravity=Gravity.START;p.topMargin=dp(6);p.bottomMargin=dp(6);parent.addView(b,p);b.setOnClickListener(v->action.run());return b;}
    Button primary(LinearLayout parent,String label,Runnable action){Button b=button(parent,label,action);b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MUTED,Color.WHITE}));PebbleSurfaces.control(b,true);return b;}
    EditText field(LinearLayout parent,String label,String value,int type){parent.addView(text(label,12,MUTED));EditText e=new EditText(this);e.setTextSize(14);e.setTextColor(INK);e.setInputType(type);e.setText(value);e.setMinHeight(dp(48));e.setPadding(dp(12),dp(8),dp(12),dp(8));PebbleSurfaces.field(e);e.setBackgroundTintList(null);e.setSaveEnabled(false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(10);parent.addView(e,p);return e;}
    ProgressBar progressBar(LinearLayout parent){ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);bar.setProgressTintList(android.content.res.ColorStateList.valueOf(SAGE));bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(SAGE));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(5));p.topMargin=dp(8);p.bottomMargin=dp(8);parent.addView(bar,p);return bar;}
    void progress(ProgressBar bar,long done,long total){bar.setIndeterminate(total<=0);if(total>0)bar.setProgress((int)Math.min(1000,Math.max(0,done)*1000/total));}
    private boolean liveQueryPending;
    private final Runnable liveTick=new Runnable(){public void run(){
        if(!liveQueryPending&&!isDestroyed()){
            liveQueryPending=true;
            executor.execute(()->{
                try{
                    int count=store.rows("SELECT count(*) AS n FROM jobs WHERE status IN ('queued','running')").getJSONObject(0).optInt("n");
                    String active=OfflineProcessor.activeId;
                    org.json.JSONArray jobs=active==null||active.isEmpty()?new org.json.JSONArray():store.rows("SELECT stage,progress_done,progress_total,progress_unit FROM jobs WHERE id=?",active);
                    runOnUiThread(()->{liveQueryPending=false;if(isDestroyed())return;
                        try{
                            if(taskEntry!=null){int total=count+(ModelDownloadService.active.isEmpty()?0:1)+(TransferProgress.current.active?1:0);setLabel(taskEntry,total>0?"任务 "+Math.min(999,total):"任务");}
                            if(livePanel!=null){TransferProgress.Snapshot transfer=TransferProgress.current;
                                if(!(AppScreen.this instanceof TaskCenterActivity)&&transfer.active){setLabel(liveText,transfer.label());progress(liveBar,transfer.received,transfer.total);livePanel.setVisibility(View.VISIBLE);}
                                else if(!(AppScreen.this instanceof TaskCenterActivity)&&OfflineProcessor.busy()&&jobs.length()>0){org.json.JSONObject job=jobs.getJSONObject(0);setLabel(liveText,"离线处理 · "+TaskProgress.describe(job));progress(liveBar,job.optLong("progress_done"),job.optLong("progress_total"));livePanel.setVisibility(View.VISIBLE);}
                                else livePanel.setVisibility(View.GONE);
                            }
                        }catch(Exception ignored){}
                    });
                }catch(Exception error){runOnUiThread(()->liveQueryPending=false);}
            });
        }
        UnderstandingAutomation.scan(AppScreen.this);live.postDelayed(this,1500);
    }};
    void onUnderstandingUpdated(){}
    protected void onResume(){super.onResume();UnderstandingAutomation.resume(this);HourlyAutomation.schedule(this,true);SyncRecovery.restore(this);OfflineScheduler.runVisible(this);live.removeCallbacks(liveTick);live.post(liveTick);}
    protected void onPause(){UnderstandingAutomation.pause(this);live.removeCallbacks(liveTick);super.onPause();}
    interface Work{void run()throws Exception;}
    void background(Work work,Runnable done){notice.setText("正在处理…");executor.execute(()->{try{work.run();runOnUiThread(()->{if(!isDestroyed()){notice.setText("已完成");done.run();}});}catch(Exception e){runOnUiThread(()->{if(!isDestroyed())notice.setText(e instanceof java.io.IOException||e instanceof IllegalArgumentException?e.getMessage():"处理失败，请检查配置、文件格式与剩余空间");});}});}
    void message(String heading,String value){new LocalDialog(this).setTitle(heading).setMessage(value).setPositiveButton("知道了",null).show();}
    void messageRaw(String heading,String value){new LocalDialog(this).setTitle(heading).rawMessage(value).setPositiveButton("知道了",null).show();}
    void editText(String heading,String value,java.util.function.Consumer<String> done){EditText input=new EditText(this);input.setText(value);input.setMinLines(3);input.setGravity(Gravity.TOP);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);new LocalDialog(this).setTitle(heading).setView(input).setPositiveButton("保存",(d,w)->done.accept(input.getText().toString())).setNegativeButton("取消",null).show();}
    String label(String value){switch(value){case "local_asr":return "离线转写与声纹";case "asr":return "云端语音转写";case "hourly_summary":return "逐小时总结";case "analysis":return "AI整理";case "life_review":return "生活分层复盘";case "vad":return "语音筛选";case "answer":return "有来源问答";case "review":return "待审核";case "queued":return "等待处理";case "running":return "处理中";case "done":return "已完成";case "failed":return "未完成";case "cancelled":return "已取消";case "uncertain":return "需要确认";case "accepted":return "已采用";default:return value;}}
    void openRecord(String id){startActivity(new android.content.Intent(this,RecordActivity.class).putExtra("id",id));}
    void startJobs(){startForegroundService(new android.content.Intent(this,AgentService.class));}
    void confirmJob(String source,String type,org.json.JSONObject input,Runnable saved){
        try{org.json.JSONObject config=input.optJSONObject("provider");String destination=config==null?"仅在本机处理":new java.net.URI(config.getString("endpoint")).getHost();if(config!=null&&(destination==null||config.optString("model").trim().isEmpty()))throw new java.io.IOException("请先配置 API");
            new LocalDialog(this).setTitle(config==null?"本地处理":"确认 AI 请求")
            .setMessage((config==null?"不上传资料。":"接收方："+destination+"\n模型："+config.optString("model")+"\n")+(type.equals("local_asr")?(input.optBoolean("speaker_only")?"只重新匹配声纹，保留文字、时间和已确认的说话人。":"使用下载的语音包转写所选录音，保留原音；已有文字将以新结果替换，人工纠正需先核对。"):type.equals("asr")?"发送当前保留的所选音频。":type.equals("analysis_batch")?"发送所选当天录音原文、时间和说话人标签，按上下文分组处理；每组先提取事实再总结（三次请求，含过滤复核），关联已确认记忆与连续事件，并附带相邻录音的少量边界文字。范围在入队时固定。":type.equals("life_review")?"发送所选时段的事实证据、待办、本人反馈和已确认记忆；分批生成有来源的日／周／长期复盘，可能产生多次请求。":type.equals("analysis")?(input.has("context_snapshot")?"发送当前原文、本地人声检测统计，以及你所选这段录音的心率、气压、光照观察。":(input.has("sources")?"发送所选连续片段的原文、时间与说话人标签，跨文件整理事件。":"发送当前原文、时间、说话人标签与本地人声检测统计。")+"附带检索到的已确认记忆、相关事件状态，以及前后相邻录音的少量边界文字。范围在入队时固定。先提取事实，再生成分段标题与总结（三次 API 请求，含过滤复核）。"):type.equals("vad")?"生成精简音频，原件保留。":"发送所选来源的原文、已采用记忆与本次问题。")+(config==null?"\n只在本机执行，不产生 API 请求。":"\n请求可能产生服务费用；不会自动重试。"))
            .setPositiveButton("开始",(d,w)->{final String[] job={""};background(()->job[0]=store.addJob(source,type,input),()->{try{if(type.equals("local_asr"))OfflineScheduler.runNow(this,job[0]);else startJobs();saved.run();}catch(Exception error){notice.setText(error.getMessage());}});}).setNegativeButton("取消",null).show();
        }catch(Exception e){notice.setText("请先配置对应 API 的地址和模型");}
    }
    public void onDestroy(){live.removeCallbacksAndMessages(null);executor.execute(()->store.close());executor.shutdown();super.onDestroy();}
}
