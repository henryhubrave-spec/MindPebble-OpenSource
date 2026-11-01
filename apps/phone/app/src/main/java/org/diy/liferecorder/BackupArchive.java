package org.diy.liferecorder;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.diy.lifecore.LocalSecurity;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Portable data backup. Credentials, permissions and pending cloud actions are never restored. */
final class BackupArchive {
    static final String[] TABLES={"audio","facts","tasks","context","tombstones","life_events",
        "event_units","atomic_facts","memory_links","life_reviews","life_feedback","correction_history",
        "filter_decisions","hourly_summaries"};
    private static final long MAX_FILE=512L*1024*1024,MAX_TOTAL=20L*1024*1024*1024;
    private static final int MAX_MANIFEST=32*1024*1024;

    static void write(RecordStore store,OutputStream output)throws Exception {
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransactionNonExclusive();
        try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(output))){
            JSONObject manifest=new JSONObject().put("backup_version",3).put("created",System.currentTimeMillis());
            for(String table:TABLES)manifest.put(table,store.rows("SELECT * FROM "+table));
            JSONObject assets=new JSONObject();JSONArray audio=manifest.getJSONArray("audio");
            for(int i=0;i<audio.length();i++){
                JSONObject row=audio.getJSONObject(i);String id=row.getString("id");
                if(!id.matches("[0-9a-f-]{36}"))throw new IOException("录音身份无效，无法备份");
                String original=row.optString("path"),speech=row.optString("vad_path");
                row.put("path",asset(zip,assets,original,"assets/"+id+"-source"));
                row.put("vad_path",speech.equals(original)?row.getString("path"):asset(zip,assets,speech,"assets/"+id+"-speech"));
            }
            manifest.put("assets",assets);byte[] bytes=manifest.toString().getBytes("UTF-8");
            if(bytes.length>MAX_MANIFEST)throw new IOException("备份资料过大，请先分批导出");
            zip.putNextEntry(new ZipEntry("records.json"));zip.write(bytes);zip.closeEntry();
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    private static String asset(ZipOutputStream zip,JSONObject assets,String path,String name)throws Exception {
        if(path.isEmpty())return "";File file=new File(path);
        if(!file.isFile())throw new IOException("原音或语音副本缺失，请先检查音频管理");
        long bytes=file.length();String sha=LocalSecurity.hash(file);
        assets.put(name,new JSONObject().put("bytes",bytes).put("sha256",sha));
        zip.putNextEntry(new ZipEntry(name));
        try(InputStream in=new FileInputStream(file)){copy(in,zip,MAX_FILE,null);}
        zip.closeEntry();
        if(bytes!=file.length()||!sha.equals(LocalSecurity.hash(file)))throw new IOException("备份期间音频已变化，请重试");
        return name;
    }

    static final class Prepared implements AutoCloseable {
        final File root;final JSONObject manifest;final Map<String,File> assets;
        Prepared(File root,JSONObject manifest,Map<String,File> assets){this.root=root;this.manifest=manifest;this.assets=assets;}
        int recordings(){return manifest.optJSONArray("audio").length();}
        public void close(){remove(root);}
    }

    static Prepared prepare(File cache,InputStream input)throws Exception {
        File root=new File(cache,"restore-"+UUID.randomUUID());if(!root.mkdirs())throw new IOException("无法创建恢复暂存目录");
        Map<String,File> files=new HashMap<>();JSONObject manifest=null;Set<String> names=new HashSet<>();long total=0;
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(input))){
            ZipEntry entry;while((entry=zip.getNextEntry())!=null){
                String name=entry.getName();
                if(!name.equals("records.json")&&!name.matches("assets/[0-9a-f-]{36}-(source|speech)"))throw new IOException("备份包含不支持的路径或内容");
                if(!names.add(name)||names.size()>100000)throw new IOException("备份有重复文件或文件过多");
                if(name.equals("records.json")){
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();total+=copy(zip,bytes,MAX_MANIFEST,root);
                    manifest=new JSONObject(bytes.toString("UTF-8"));
                }else{
                    File file=new File(root,UUID.randomUUID().toString());
                    try(FileOutputStream out=new FileOutputStream(file)){total+=copy(zip,out,MAX_FILE,root);out.getFD().sync();}
                    files.put(name,file);
                }
                if(total>MAX_TOTAL)throw new IOException("备份解压内容过大");zip.closeEntry();
            }
            if(manifest==null||manifest.optInt("backup_version")!=3)throw new IOException("请选择完整备份 v3；选择性导出及旧版 ZIP 不能用于整包恢复");
            JSONObject expected=manifest.getJSONObject("assets");
            if(expected.length()!=files.size())throw new IOException("备份文件清单不完整");
            for(String name:files.keySet()){
                JSONObject asset=expected.getJSONObject(name);File file=files.get(name);
                if(asset.getLong("bytes")!=file.length()||!asset.getString("sha256").equals(LocalSecurity.hash(file)))throw new IOException("备份音频校验失败");
            }
            for(String table:TABLES)if(manifest.getJSONArray(table).length()>100000)throw new IOException("备份记录过多");
            return new Prepared(root,manifest,files);
        }catch(Exception error){remove(root);throw error;}
    }

    static void restore(RecordStore store,Prepared prepared)throws Exception {
        SQLiteDatabase db=store.getWritableDatabase();List<File> moved=new ArrayList<>();boolean successful=false;
        db.beginTransaction();try{
            for(String table:TABLES)if(store.rows("SELECT 1 FROM "+table+" LIMIT 1").length()>0)throw new IOException("恢复仅支持空资料库，请使用未导入资料的新安装；不会覆盖现有记录");
            if(store.rows("SELECT 1 FROM jobs LIMIT 1").length()>0)throw new IOException("请先处理现有任务，不能在运行中恢复");
            JSONArray recordings=prepared.manifest.getJSONArray("audio");
            for(int i=0;i<recordings.length();i++){
                JSONObject recording=recordings.getJSONObject(i);String path=recording.getString("path");
                if(!path.isEmpty()){
                    JSONObject asset=prepared.manifest.getJSONObject("assets").getJSONObject(path);
                    String expected=recording.optInt("source_retained",1)==1?recording.getString("sha"):recording.getString("retained_sha");
                    if(!asset.getString("sha256").equals(expected))throw new IOException("备份音频与来源校验值不一致");
                }
            }
            Map<String,String> paths=new HashMap<>();
            for(Map.Entry<String,File> asset:prepared.assets.entrySet()){
                File destination=new File(store.media,"restored-"+UUID.randomUUID()+".m4a");
                if(destination.exists()||!asset.getValue().renameTo(destination))throw new IOException("无法保存恢复音频");
                moved.add(destination);paths.put(asset.getKey(),destination.getPath());
            }
            LocalSecurity.syncDirectory(store.media);
            for(String table:TABLES){
                Set<String> columns=new HashSet<>();try(Cursor cursor=db.rawQuery("PRAGMA table_info("+table+")",null)){while(cursor.moveToNext())columns.add(cursor.getString(1));}
                JSONArray rows=prepared.manifest.getJSONArray(table);
                for(int i=0;i<rows.length();i++){
                    JSONObject row=new JSONObject(rows.getJSONObject(i).toString());
                    if(table.equals("audio"))for(String field:new String[]{"path","vad_path"}){
                        String asset=row.getString(field);if(!asset.isEmpty()&&!paths.containsKey(asset))throw new IOException("备份缺少引用音频");row.put(field,asset.isEmpty()?"":paths.get(asset));
                    }
                    if(table.equals("hourly_summaries"))row.put("job_id","");
                    ContentValues values=new ContentValues();Iterator<String> keys=row.keys();
                    while(keys.hasNext()){
                        String key=keys.next();if(!columns.contains(key))throw new IOException("备份字段与本版本不兼容");
                        Object value=row.get(key);if(value==JSONObject.NULL)values.putNull(key);else if(value instanceof Number)values.put(key,((Number)value).longValue());else if(value instanceof String)values.put(key,(String)value);else throw new IOException("备份字段格式错误");
                    }
                    db.insertOrThrow(table,null,values);
                }
            }
            JSONArray memories=store.rows("SELECT id FROM facts WHERE state='accepted'");
            for(int i=0;i<memories.length();i++)LifeMemory.index(store,memories.getJSONObject(i).getString("id"));
            // Restoring data grants no permission to send it to any provider.
            if(!store.context.getSharedPreferences("understanding",0).edit().putBoolean("auto_events",false).putBoolean("hourly_enabled",false).commit())throw new IOException("无法关闭自动云端处理");
            db.setTransactionSuccessful();successful=true;
        }finally{
            try{db.endTransaction();}catch(Exception error){for(File file:moved)file.delete();throw error;}
            if(!successful)for(File file:moved)file.delete();
        }
    }

    private static long copy(InputStream in,OutputStream out,long limit,File storage)throws IOException {
        byte[] buffer=new byte[65536];long total=0;int n;
        while((n=in.read(buffer))!=-1){total+=n;if(total>limit||Thread.currentThread().isInterrupted())throw new IOException("备份文件过大或操作已中断");if(storage!=null&&storage.getUsableSpace()<64L*1024*1024)throw new IOException("恢复空间不足");out.write(buffer,0,n);}return total;
    }
    static void recoverStaging(File cache,long cutoff){
        File[] files=cache.listFiles();if(files==null)return;
        for(File file:files)if(file.isDirectory()&&file.getName().matches("restore-[0-9a-f-]{36}")&&file.lastModified()<cutoff)remove(file);
    }
    private static void remove(File root){File[] files=root.listFiles();if(files!=null)for(File file:files)remove(file);root.delete();}
}
