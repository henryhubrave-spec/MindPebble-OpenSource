package org.diy.watchrecorder;

import android.content.*;
import java.io.*;
import org.json.*;
import org.diy.lifecore.LocalSecurity;

/** Cache immutable, private finalized files. Destructive operations always hash afresh. */
final class FinalizedDigest {
    static long bytesHashed;
    static synchronized String get(Context c,File file)throws Exception{
        if(!file.isFile()||!(file.getName().endsWith(".m4a")||file.getName().endsWith(".context.jsonl")))throw new IOException("只校验已保存文件");
        SharedPreferences prefs=c.getSharedPreferences("finalized-digests",0);String key=file.getCanonicalPath();long bytes=file.length(),mtime=file.lastModified();
        try{JSONObject cached=new JSONObject(prefs.getString(key,"{}"));String sha=cached.optString("sha");if(mtime>0&&cached.optLong("bytes",-1)==bytes&&cached.optLong("mtime",-1)==mtime&&sha.matches("[a-f0-9]{64}"))return sha;}catch(JSONException ignored){}
        String sha=LocalSecurity.hash(file);bytesHashed+=bytes;if(!file.isFile()||bytes!=file.length()||mtime!=file.lastModified())throw new IOException("文件校验期间发生变化");
        if(!prefs.edit().putString(key,new JSONObject().put("bytes",bytes).put("mtime",mtime).put("sha",sha).toString()).commit())throw new IOException("无法缓存文件校验");return sha;
    }
    static synchronized void forget(Context c,File file){try{c.getSharedPreferences("finalized-digests",0).edit().remove(file.getCanonicalPath()).commit();}catch(IOException ignored){}}
}
