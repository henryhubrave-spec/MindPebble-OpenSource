package org.diy.watchrecorder;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import java.io.*;
import org.json.*;

/** Recover only a decodable, journaled interrupted segment; retain unplayable evidence. */
final class RecordingRecovery {
    static void inspect(Context context)throws Exception{
        File directory=new File(context.getFilesDir(),"recordings");File[] pending=directory.listFiles((d,n)->n.endsWith(".m4a.part.capture.json"));if(pending==null)return;int recovered=0,incomplete=0;
        for(File journal:pending){if(RecordingService.alive)return;File source=new File(journal.getPath().replace(".capture.json",""));File complete=new File(directory,source.getName().replace(".part",""));
            if(new File(directory,complete.getName()+".json").isFile()){journal.delete();continue;}
            boolean alreadyRenamed=!source.exists()&&complete.isFile();if(alreadyRenamed)source=complete;if(!source.isFile()){incomplete++;continue;}
            try{
                JSONObject started=new JSONObject(new String(java.nio.file.Files.readAllBytes(journal.toPath()),"UTF-8"));long duration;
                MediaMetadataRetriever media=new MediaMetadataRetriever();try{media.setDataSource(source.getPath());duration=Long.parseLong(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));}finally{media.release();}
                if(duration<=0)throw new IOException("incomplete audio");if(complete.exists()&&!alreadyRenamed)throw new IOException("existing segment");
                JSONObject meta=new JSONObject().put("schema_version",1).put("session_id",started.getString("session_id")).put("sequence",started.getInt("sequence")).put("audio_file",complete.getName()).put("started_at_epoch_ms",started.getLong("started_at_epoch_ms")).put("ended_at_epoch_ms",started.getLong("started_at_epoch_ms")+duration).put("capture_elapsed_ms",duration).put("gap_before_ms",started.optLong("gap_before_ms")).put("reason","recovered_after_interruption").put("markers",started.optJSONArray("markers")==null?new JSONArray():started.getJSONArray("markers")).put("bytes",source.length()).put("sync_state","local_only");
                File temp=new File(directory,complete.getName()+".json.tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(meta.toString().getBytes("UTF-8"));out.getFD().sync();}
                if(!alreadyRenamed&&!source.renameTo(complete))throw new IOException("audio commit failed");if(!temp.renameTo(new File(directory,complete.getName()+".json"))){if(!alreadyRenamed)complete.renameTo(source);throw new IOException("metadata commit failed");}journal.delete();recovered++;
            }catch(Exception e){incomplete++;}
        }
        if(!RecordingService.alive&&(recovered>0||incomplete>0))context.getSharedPreferences("recorder",0).edit().putString("state",CaptureIntent.mode(context).equals(CaptureIntent.RUNNING)?"recovering":"error").putString("message","上次记录已中断 · 恢复 "+recovered+" 段 · 未完成 "+incomplete+" 段保留待检查"+(CaptureIntent.mode(context).equals(CaptureIntent.RUNNING)?"；等待自动恢复":"；请手动重新开始")).commit();
    }
}
