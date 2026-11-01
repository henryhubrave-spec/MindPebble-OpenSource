package org.diy.liferecorder;

import android.content.*;
import android.app.ActivityManager;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import org.diy.lifecore.LocalSecurity;

/** Pinned, data-only model catalog. No downloaded code; original recordings are unrelated. */
final class OfflinePacks {
    static final Object ENGINE_LOCK = new Object();
    final Context context;
    final File root;
    final SharedPreferences prefs;
    private final JSONArray catalog;
    OfflinePacks(Context c) throws Exception {
        context=c.getApplicationContext();root=new File(c.getFilesDir(),"offline-packs");
        if(!root.isDirectory()&&!root.mkdirs()&&!root.isDirectory())throw new IOException("无法创建离线资源目录");
        prefs=c.getSharedPreferences("offline-packs-v1",0);
        try(InputStream in=c.getAssets().open("offline-catalog.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);catalog=new JSONObject(out.toString("UTF-8")).getJSONArray("packs");}
    }
    JSONArray all(){return catalog;}
    JSONObject pack(String id)throws Exception{for(int i=0;i<catalog.length();i++)if(catalog.getJSONObject(i).getString("id").equals(id))return catalog.getJSONObject(i);throw new IOException("未知离线资源");}
    File directory(String id)throws Exception{pack(id);return new File(root,id);}
    File file(String id,String name)throws Exception{return new File(directory(id),name);}
    long bytes(String id)throws Exception{JSONArray fs=pack(id).getJSONArray("files");long n=0;for(int i=0;i<fs.length();i++)n+=fs.getJSONObject(i).getLong("bytes");return n;}
    boolean installed(String id)throws Exception{
        JSONObject p=pack(id);File marker=file(id,"installed.json");if(!marker.isFile())return false;
        try{JSONObject m=new JSONObject(new String(java.nio.file.Files.readAllBytes(marker.toPath()),"UTF-8"));if(!p.getString("version").equals(m.optString("version")))return false;JSONArray fs=p.getJSONArray("files");for(int i=0;i<fs.length();i++){JSONObject f=fs.getJSONObject(i);if(file(id,f.getString("name")).length()!=f.getLong("bytes"))return false;}return true;}catch(Exception e){return false;}
    }
    boolean ready(String id)throws Exception{return installed(id)&&prefs.getString(id+"-tested","").equals(pack(id).getString("version"));}
    void verify(String id,File dir)throws Exception{
        JSONArray fs=pack(id).getJSONArray("files");for(int i=0;i<fs.length();i++){JSONObject f=fs.getJSONObject(i);File v=new File(dir,f.getString("name"));if(!v.isFile()||v.length()!=f.getLong("bytes")||!LocalSecurity.hash(v).equals(f.getString("sha256")))throw new IOException("资源校验失败，请重新下载："+f.getString("name"));}
    }
    String selected(){String id=prefs.getString("selected","");if(AppLanguage.code(context).equals("en")&&id.equals("lite")){try{return ready("standard")?"standard":"";}catch(Exception e){return "";}}return id;}
    boolean automatic(){return prefs.getBoolean("automatic",false);}
    boolean useMirror(){return prefs.getBoolean("mirror",java.util.Locale.getDefault().getLanguage().equals("zh"));}
    String downloadUrl(String original){return useMirror()&&original.startsWith("https://huggingface.co/")?"https://hf-mirror.com/"+original.substring("https://huggingface.co/".length()):original;}
    boolean chargingOnly(){return prefs.getBoolean("charging-only",true);}
    void set(String key,boolean value){if(!prefs.edit().putBoolean(key,value).commit())throw new IllegalStateException("设置保存失败");}
    String recommended(){ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(m);
        try{JSONObject benchmark=new JSONObject(prefs.getString("standard-benchmark","{}"));if(benchmark.optDouble("rtf",0)>.6||prefs.getInt("standard-slow-count",0)>=3)return "lite";}catch(Exception ignored){}
        return m.totalMem>=6L*1024*1024*1024&&!m.lowMemory?"standard":"lite";}

    String deviceSummary(){ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(m);return String.format(Locale.CHINA,"内存 %.1f GB · 当前可用 %.1f GB · 资源空间 %.1f GB",m.totalMem/1073741824.0,m.availMem/1073741824.0,root.getUsableSpace()/1073741824.0);}
    String state(String id)throws Exception{
        if(ModelDownloadService.active.equals(id))return ModelDownloadService.status;
        String error=prefs.getString(id+"-error","");
        if(ready(id))return "可离线使用"+(selected().equals(id)?" · 当前方案":"");
        if(installed(id))return "已下载 · 等待运行验证"+(error.isEmpty()?"":"\n"+error);
        File stage=new File(root,id+".partial");long saved=0;File[] files=stage.listFiles();if(files!=null)for(File f:files)if(f.getName().endsWith(".part"))saved+=f.length();
        return (saved>0?String.format(Locale.CHINA,"已暂停 · %.1f / %.1f MB",saved/1048576.0,bytes(id)/1048576.0):"未下载")+(error.isEmpty()?"":"\n"+error);
    }
    void select(String id)throws Exception{if(id.equals("speaker")||!ready(id))throw new IOException("请先下载并通过运行验证");if(!prefs.edit().putString("selected",id).commit())throw new IOException("设置保存失败");}
    void remove(String id)throws Exception{
        synchronized(ENGINE_LOCK){if(ModelDownloadService.active.equals(id))throw new IOException("请先暂停下载或验证");
            if(selected().equals(id))prefs.edit().remove("selected").putBoolean("automatic",false).commit();
            prefs.edit().remove(id+"-tested").remove(id+"-benchmark").remove(id+"-error").commit();
            deleteTree(directory(id));deleteTree(new File(root,id+".partial"));
            try(RecordStore db=new RecordStore(context)){db.getWritableDatabase().execSQL("UPDATE jobs SET status='cancelled',error='离线资源包已删除' WHERE type='local_asr' AND status='queued' AND input LIKE ?",new Object[]{"%\"pack\":\""+id+"\"%"});}

        }
    }
    static void deleteTree(File f)throws IOException{File[] children=f.listFiles();if(children!=null)for(File c:children)deleteTree(c);if(f.exists()&&!f.delete())throw new IOException("资源删除失败，请重试");}
}
