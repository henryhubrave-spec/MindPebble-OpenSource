package org.diy.watchrecorder;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import org.json.JSONObject;

/** Small private event journal: no audio, transcripts, phone numbers or pairing credentials. */
final class CaptureDiagnostics {
    static synchronized void event(Context context,String event,String detail){
        try{
            File dir=new File(context.getFilesDir(),"diagnostics");if(!dir.isDirectory()&&!dir.mkdirs())return;
            File log=new File(dir,"capture.jsonl");if(log.length()>96*1024){File old=new File(dir,"capture.previous.jsonl");if(old.exists()&&!old.delete())return;if(!log.renameTo(old))return;}
            String safe=detail==null?"":detail.replace('\n',' ').replace('\r',' ');if(safe.length()>240)safe=safe.substring(0,240);
            JSONObject row=new JSONObject().put("at_ms",System.currentTimeMillis()).put("elapsed_ms",SystemClock.elapsedRealtime()).put("event",event).put("detail",safe);
            try(FileOutputStream out=new FileOutputStream(log,true)){out.write((row.toString()+"\n").getBytes("UTF-8"));out.getFD().sync();}
        }catch(Exception ignored){}
    }
}
