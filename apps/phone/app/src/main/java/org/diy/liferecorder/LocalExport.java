package org.diy.liferecorder;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Portable exports. Selected exports are an explicit allowlist, never a database dump. */
final class LocalExport {
    static final int ORIGINAL=1,TRANSCRIPT=2,HIGHLIGHTS=4,SUMMARY=8,DAILY=16,ALL=31,TEXT=30;
    interface Progress { void update(String label,long done,long total); }
    interface Cancel { boolean requested(); }
    static final class Cancelled extends IOException { Cancelled(){super("导出已取消");} }
    static final class Options {
        final long start,end; final int kinds; final boolean workflow;final String sourceId;
        Options(long start,long end,int kinds,boolean workflow) {this(start,end,kinds,workflow,"");}
        Options(long start,long end,int kinds,boolean workflow,String sourceId) {
            if(start<0||end<=start)throw new IllegalArgumentException("结束日期不能早于开始日期");
            if((kinds&ALL)==0||(kinds&~ALL)!=0)throw new IllegalArgumentException("请至少选择一种内容");
            if(workflow&&(kinds&ORIGINAL)!=0)throw new IllegalArgumentException("工作流资料包只包含文字，请使用自定义导出保存原音");
            this.start=start;this.end=end;this.kinds=kinds;this.workflow=workflow;this.sourceId=sourceId==null?"":sourceId;
        }
        boolean has(int kind){return (kinds&kind)!=0;}
    }
    static final class Entry {
        final String name;final byte[] text;final File file;final long bytes,modified;
        Entry(String name,String text)throws UnsupportedEncodingException{this.name=name;this.text=text.getBytes("UTF-8");file=null;bytes=this.text.length;modified=0;}
        Entry(String name,File file){this.name=name;this.file=file;text=null;bytes=file.length();modified=file.lastModified();}
    }
    static final class Plan {
        final Options options;final ArrayList<Entry> entries=new ArrayList<>();final ArrayList<String> warnings=new ArrayList<>();
        final JSONArray sources=new JSONArray();final int[] counts=new int[5];long totalBytes;int contentFiles;
        Plan(Options options){this.options=options;}
        void add(Entry entry){entries.add(entry);totalBytes+=entry.bytes;contentFiles++;}
        String describe(){StringBuilder out=new StringBuilder();String[] names={"原音","转写","重点","总结","日回顾"};int[] flags={ORIGINAL,TRANSCRIPT,HIGHLIGHTS,SUMMARY,DAILY};for(int i=0;i<flags.length;i++)if(options.has(flags[i])){if(out.length()>0)out.append(" · ");out.append(names[i]).append(' ').append(counts[i]);}return out.toString();}
    }

    /** Retained for the separate full-backup action. */
    static void write(RecordStore db,OutputStream destination)throws Exception{
        BackupArchive.write(db,destination);
    }

    /** Freeze database content before writing. Recordings arriving later belong to the next export. */
    static Plan prepare(RecordStore db,Options options,Cancel cancel)throws Exception{
        Plan plan=new Plan(options);JSONArray audio,reviews,hours;
        db.getReadableDatabase().beginTransactionNonExclusive();
        try {
            audio=db.rows("SELECT id,title,path,bytes,duration,created,metadata,transcript,speech_json,analysis,source_retained,transcript_revision FROM audio WHERE created>=? AND created<? AND (?='' OR id=?) ORDER BY created,id",String.valueOf(options.start),String.valueOf(options.end),options.sourceId,options.sourceId);
            reviews=options.has(DAILY)?db.rows("SELECT id,kind,start,end,result,evidence,created,stale FROM life_reviews WHERE kind='daily' AND start>=? AND end<=? ORDER BY start,created",String.valueOf(options.start),String.valueOf(options.end)):new JSONArray();
            hours=options.has(SUMMARY)?db.rows("SELECT * FROM hourly_summaries WHERE dirty=0 AND result<>'' AND start>=? AND end<=? AND zone=? ORDER BY start",String.valueOf(options.start),String.valueOf(options.end),java.time.ZoneId.systemDefault().getId()):new JSONArray();
            for(int i=hours.length()-1;i>=0;i--)try{HourlySummaries.checkCurrent(db,new JSONObject(hours.getJSONObject(i).getString("evidence")));}catch(Exception changed){hours.remove(i);plan.warnings.add("小时总结的来源已变更，已跳过");}
            db.getReadableDatabase().setTransactionSuccessful();
        } finally { db.getReadableDatabase().endTransaction(); }
        Set<String> selected=new HashSet<>();for(int i=0;i<audio.length();i++)selected.add(audio.getJSONObject(i).getString("id"));
        for(int i=0;i<audio.length();i++){
            check(cancel);JSONObject a=audio.getJSONObject(i);String id=a.getString("id"),stem=safe(id);int before=plan.contentFiles;
            if(options.has(ORIGINAL)){
                File file=new File(a.optString("path"));
                if(a.optInt("source_retained",1)==1&&file.isFile()&&file.length()>0){plan.add(new Entry("audio/"+stem+extension(file),file));plan.counts[0]++;}
                else plan.warnings.add("原音不可用，已跳过："+id+"（未同步、已清理或仅保留精简音频）");
            }
            if(options.has(TRANSCRIPT)&&!a.optString("transcript").trim().isEmpty()){
                JSONArray units=TranscriptDocument.units(a,true);plan.counts[1]++;
                plan.add(new Entry("transcripts/"+stem+".txt",transcript(a,units)));
                plan.add(new Entry("transcripts/"+stem+".json",new JSONObject().put("source",source(a)).put("units",units).toString(2)));
            }
            if(options.has(HIGHLIGHTS)){
                JSONArray highlights=highlights(a);
                if(highlights.length()>0){plan.counts[2]++;StringBuilder md=new StringBuilder(header(a,"重点"));for(int n=0;n<highlights.length();n++){JSONObject h=highlights.getJSONObject(n);md.append("- ").append(h.optString("at")).append(" · +").append(offset(h.optLong("offset_ms"))).append(" · ").append(h.optString("text","手表重点标记")).append('\n');}
                    plan.add(new Entry("highlights/"+stem+".md",md.toString()));plan.add(new Entry("highlights/"+stem+".json",new JSONObject().put("source",source(a)).put("highlights",highlights).toString(2)));}
            }
            if(options.has(SUMMARY)&&!a.optString("analysis").trim().isEmpty()){
                JSONObject analysis=object(a.optString("analysis"));JSONArray refs=analysis.optJSONArray("sources");boolean allowed=true;
                if(refs!=null)for(int n=0;n<refs.length();n++){JSONObject ref=refs.optJSONObject(n);if(ref==null||!selected.contains(ref.optString("source_id",ref.optString("id"))))allowed=false;}
                if(!allowed)plan.warnings.add("总结引用了日期范围外的录音，已跳过："+id);
                else if(!analysis.optString("summary").trim().isEmpty()){plan.counts[3]++;
                    JSONArray sourceIds=new JSONArray();if(refs!=null)for(int n=0;n<refs.length();n++)sourceIds.put(refs.getJSONObject(n).optString("source_id",refs.getJSONObject(n).optString("id")));if(sourceIds.length()==0)sourceIds.put(id);
                    plan.add(new Entry("summaries/"+stem+".md",header(a,"总结")+"引用来源："+sourceIds.toString()+"\n\nAI 生成，需对照原文核对。\n\n"+analysis.optString("summary")+"\n"));
                } else plan.warnings.add("总结格式不完整，已跳过："+id);
            }
            if(plan.contentFiles>before)plan.sources.put(source(a));
        }
        for(int i=0;i<hours.length();i++){
            check(cancel);JSONObject hour=hours.getJSONObject(i),result=object(hour.optString("result"));JSONArray claims=result.optJSONArray("claims");if(claims==null||claims.length()==0)continue;
            boolean allowed=true;Set<String> used=new HashSet<>();for(int n=0;n<claims.length();n++){JSONArray refs=claims.getJSONObject(n).getJSONArray("citations");for(int r=0;r<refs.length();r++){String source=refs.getJSONObject(r).getString("source_id");used.add(source);if(!selected.contains(source))allowed=false;}}
            if(!allowed){plan.warnings.add("小时总结引用了所选范围外的录音，已跳过："+HourlySummaries.label(hour));continue;}
            StringBuilder md=new StringBuilder("# 逐小时总结 · "+HourlySummaries.label(hour)+"\n\n日期："+timestamp(hour.optLong("start"))+"\n时区："+hour.optString("zone")+"\n\nAI 整理；跨整点语句按开始时间归档。\n\n");
            for(int n=0;n<claims.length();n++){JSONObject claim=claims.getJSONObject(n);md.append("- [").append(claim.optString("kind")).append("] ").append(claim.optString("text")).append('\n');JSONArray refs=claim.getJSONArray("citations");for(int r=0;r<refs.length();r++){JSONObject ref=refs.getJSONObject(r);md.append("  > ").append(ref.optString("quote")).append("\n  来源：").append(ref.optString("source_id")).append(" · +").append(offset(ref.optLong("start_ms"))).append('\n');}}
            JSONObject clean=new JSONObject().put("start",hour.getLong("start")).put("end",hour.getLong("end")).put("timezone",hour.getString("zone")).put("generated_at",hour.getLong("updated")).put("claims",claims);
            plan.add(new Entry("hourly-summaries/"+safe(hour.getString("id"))+".md",md.toString()));plan.add(new Entry("hourly-summaries/"+safe(hour.getString("id"))+".json",clean.toString(2)));plan.counts[3]++;
            Set<String> already=new HashSet<>();for(int n=0;n<plan.sources.length();n++)already.add(plan.sources.getJSONObject(n).optString("source_id"));for(int n=0;n<audio.length();n++){JSONObject a=audio.getJSONObject(n);if(used.contains(a.getString("id"))&&already.add(a.getString("id")))plan.sources.put(source(a));}
        }
        for(int i=0;i<reviews.length();i++){
            check(cancel);JSONObject review=reviews.getJSONObject(i);
            if(review.optInt("stale")!=0){plan.warnings.add("日回顾的来源已变更，已跳过："+review.optString("id"));continue;}
            JSONObject result=object(review.optString("result")),evidence=object(review.optString("evidence"));JSONArray claims=result.optJSONArray("claims");
            if(claims==null||claims.length()==0)continue;
            JSONArray accepted=new JSONArray();Map<String,JSONObject> evidenceMap=reviewEvidence(evidence,selected,options);
            for(int n=0;n<claims.length();n++){JSONObject claim=claims.getJSONObject(n);JSONArray refs=claim.optJSONArray("evidence_ids");boolean allowed=refs!=null&&refs.length()>0;if(refs!=null)for(int j=0;j<refs.length();j++)allowed&=evidenceMap.containsKey(refs.optString(j));if(allowed)accepted.put(new JSONObject().put("section",claim.optString("section")).put("kind",claim.optString("kind")).put("text",claim.optString("text")).put("evidence_ids",refs));}
            if(accepted.length()!=claims.length())plan.warnings.add("日回顾部分结论引用范围外的资料，已跳过这些结论："+review.optString("id"));
            if(accepted.length()==0)continue;plan.counts[4]++;
            StringBuilder md=new StringBuilder("# 每日回顾\n\n回顾 ID："+review.optString("id")+"\n日期："+timestamp(review.optLong("start"))+" — "+timestamp(review.optLong("end"))+"（结束时间不含）\n生成时间："+timestamp(review.optLong("created"))+"\n\nAI 生成，推测与本人反馈按条目标注。\n\n");JSONArray used=new JSONArray();Set<String> usedIds=new HashSet<>();
            for(int n=0;n<accepted.length();n++){JSONObject claim=accepted.getJSONObject(n);md.append("- [").append(claim.optString("section")).append(" / ").append(claim.optString("kind")).append("] ").append(claim.optString("text")).append("\n  依据：").append(claim.optJSONArray("evidence_ids")).append('\n');JSONArray refs=claim.getJSONArray("evidence_ids");for(int j=0;j<refs.length();j++){String id=refs.getString(j);if(usedIds.add(id))used.put(evidenceMap.get(id));}}
            Set<String> citedSources=new HashSet<>();for(int n=0;n<used.length();n++)citedSources.add(used.getJSONObject(n).optString("source"));Set<String> already=new HashSet<>();for(int n=0;n<plan.sources.length();n++)already.add(plan.sources.getJSONObject(n).optString("source_id"));for(int n=0;n<audio.length();n++){JSONObject source=audio.getJSONObject(n);if(citedSources.contains(source.optString("id"))&&already.add(source.optString("id")))plan.sources.put(source(source));}
            JSONObject clean=new JSONObject().put("id",review.optString("id")).put("start",review.optLong("start")).put("end",review.optLong("end")).put("claims",accepted).put("evidence",used);
            plan.add(new Entry("daily-reviews/"+safe(review.optString("id"))+".md",md.toString()));plan.add(new Entry("daily-reviews/"+safe(review.optString("id"))+".json",clean.toString(2)));
        }
        if(plan.contentFiles==0)throw new IOException("所选日期与内容类型没有可导出的资料。请扩大日期范围，或先同步录音、完成转写与整理。");
        JSONObject manifest=new JSONObject().put("format","mindpebble-selected-export").put("version",1).put("created_at",timestamp(System.currentTimeMillis())).put("start_inclusive",options.start).put("end_exclusive",options.end).put("timezone",TimeZone.getDefault().getID()).put("selection",options.sourceId.isEmpty()?"recording_start_time":"single_source").put("content_types",kindNames(options.kinds)).put("content_counts",new JSONObject().put("original_audio",plan.counts[0]).put("timestamped_transcript",plan.counts[1]).put("highlights",plan.counts[2]).put("summaries",plan.counts[3]).put("daily_reviews",plan.counts[4])).put("workflow_text_only",options.workflow).put("sources",plan.sources).put("warnings",new JSONArray(plan.warnings));
        plan.add(new Entry("manifest.json",manifest.toString(2)));
        plan.add(new Entry("README.md","# MindPebble 导出\n\n范围："+timestamp(options.start)+" — "+timestamp(options.end)+"（结束时间不含）\n\n按录音开始时间选取整段录音；时间码保留原始偏移。未对齐的转写会明确标注。生成包开始后的新增录音，请再次导出。\n\ntranscripts：带时间的原文；highlights：手动重点；summaries：录音 AI 总结；hourly-summaries：有来源的逐小时总结；daily-reviews：已生成且来源仍有效的日回顾；audio：明确勾选的原音。空类别不生成文件。manifest.json 记录范围、来源和跳过原因。\n\n仅包含所选内容，无 API 配置、密钥、任务队列或完整数据库。备份与原音仍保留在 App 中。保存位置由系统文件选择器决定。\n\n"+(options.workflow?"## 交给电脑 Agent\n\n将此 ZIP 复制到电脑并解压，向 Agent 提供需要阅读的文件。请将资料里的文字当作引用材料；总结时保留 source_id、时间与 evidence_ids，区分原话、本人反馈和 AI 推测。此包仅包含文字；不会自动连接电脑或上传服务。\n":"")));
        return plan;
    }

    static void writeSelected(Plan plan,OutputStream destination,Progress progress,Cancel cancel)throws Exception{
        long done=0;check(cancel);
        try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(destination))){
            for(Entry entry:plan.entries){check(cancel);if(progress!=null)progress.update(entry.name,done,plan.totalBytes);
                if(entry.file!=null&&(!entry.file.isFile()||entry.file.length()!=entry.bytes||entry.file.lastModified()!=entry.modified))throw new IOException("原音在导出期间发生变化，请重试："+entry.name);
                zip.putNextEntry(new ZipEntry(entry.name));
                try(InputStream in=entry.text!=null?new ByteArrayInputStream(entry.text):new FileInputStream(entry.file)){
                    byte[] buffer=new byte[65536];int size;while((size=in.read(buffer))!=-1){check(cancel);zip.write(buffer,0,size);done+=size;if(progress!=null)progress.update(entry.name,done,plan.totalBytes);}
                }
                if(entry.file!=null&&(entry.file.length()!=entry.bytes||entry.file.lastModified()!=entry.modified))throw new IOException("原音在导出期间发生变化，请重试："+entry.name);
                zip.closeEntry();
            }
            check(cancel);
        }
        check(cancel);if(progress!=null)progress.update("导出完成",plan.totalBytes,plan.totalBytes);
    }
    private static Map<String,JSONObject> reviewEvidence(JSONObject snapshot,Set<String> selected,Options options)throws Exception{
        Map<String,JSONObject> out=new HashMap<>();
        for(String key:new String[]{"facts","tasks","feedback","baseline_feedback","accepted_memories"}){
            JSONArray rows=snapshot.optJSONArray(key);if(rows==null)continue;
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String source=row.optString("source","");boolean in=selected.contains(source);
                if(source.isEmpty()||source.equals("null"))in=row.optLong("created")>=options.start&&row.optLong("created")<options.end;
                if(!in)continue;JSONObject clean=new JSONObject();for(String field:new String[]{"id","source","unit_id","text","quote","type","state","created","note","energy","accuracy"})if(row.has(field))clean.put(field,row.get(field));clean.put("evidence_type",key);out.put(row.optString("id"),clean);
            }
        }
        return out;
    }
    static JSONArray highlights(JSONObject audio)throws Exception{
        JSONArray out=new JSONArray(),markers=object(audio.optString("metadata")).optJSONArray("markers");if(markers==null)return out;
        for(int i=0;i<markers.length();i++){JSONObject marker=markers.optJSONObject(i);if(marker==null)continue;long offset=marker.optLong("offset_ms",-1);if(offset<0||offset>=audio.optLong("duration",Long.MAX_VALUE))continue;String text=marker.optString("text",marker.optString("note",marker.optString("label","手表重点标记")));
            out.put(new JSONObject().put("source_id",audio.optString("id")).put("offset_ms",offset).put("at",timestamp(audio.optLong("created")+offset)).put("text",text));}
        return out;
    }
    static String transcript(JSONObject audio,JSONArray units)throws Exception{
        StringBuilder out=new StringBuilder(header(audio,"逐段转写"));
        for(int i=0;i<units.length();i++){JSONObject u=units.getJSONObject(i);out.append(u.optBoolean("timed")?"["+timestamp(audio.optLong("created")+u.optLong("start_ms"))+" — "+timestamp(audio.optLong("created")+u.optLong("end_ms"))+"] [+"+offset(u.optLong("start_ms"))+" — +"+offset(u.optLong("end_ms"))+"]":"[时间未对齐]").append(" · ").append(u.optString("speaker")).append("\n").append(u.optString("text")).append("\n\n");}
        return out.toString();
    }
    private static JSONObject source(JSONObject a)throws Exception{return new JSONObject().put("source_id",a.getString("id")).put("title",a.optString("title")).put("started_at",timestamp(a.optLong("created"))).put("created",a.optLong("created")).put("duration_ms",a.optLong("duration")).put("transcript_revision",a.optLong("transcript_revision"));}
    private static String header(JSONObject a,String label){return "# "+label+" · "+a.optString("title")+"\n\n来源 ID："+a.optString("id")+"\n开始时间："+timestamp(a.optLong("created"))+"\n时长："+offset(a.optLong("duration"))+"\n\n";}
    private static JSONObject object(String value){try{return new JSONObject(value);}catch(Exception ignored){return new JSONObject();}}
    private static void check(Cancel cancel)throws Cancelled{if(Thread.currentThread().isInterrupted()||(cancel!=null&&cancel.requested()))throw new Cancelled();}
    static String safe(String name){return Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static String extension(File file){String name=file.getName().toLowerCase(Locale.ROOT);int dot=name.lastIndexOf('.');String ext=dot>=0?name.substring(dot):"";return ext.matches("\\.(m4a|wav|mp3|aac|flac|ogg|opus|mp4)")?ext:".audio";}
    static String timestamp(long at){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z",Locale.CHINA).format(new Date(at));}
    static String offset(long ms){long value=Math.max(0,ms);return String.format(Locale.ROOT,"%02d:%02d:%02d.%03d",value/3600000,value/60000%60,value/1000%60,value%1000);}
    private static JSONArray kindNames(int kinds){JSONArray names=new JSONArray();int[] flags={ORIGINAL,TRANSCRIPT,HIGHLIGHTS,SUMMARY,DAILY};String[] values={"original_audio","timestamped_transcript","highlights","summaries","daily_reviews"};for(int i=0;i<flags.length;i++)if((kinds&flags[i])!=0)names.put(values[i]);return names;}
    private static void add(ZipOutputStream zip,File file,String path)throws Exception{zip.putNextEntry(new ZipEntry(path));try(InputStream input=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=input.read(b))!=-1)zip.write(b,0,n);}zip.closeEntry();}
}
