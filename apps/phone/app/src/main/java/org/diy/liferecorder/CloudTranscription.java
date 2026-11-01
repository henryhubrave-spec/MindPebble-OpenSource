package org.diy.liferecorder;
import org.json.*;import java.io.*;import java.net.*;

/** Workstation upload/job protocol and lossless normalization of timed ASR responses. */
final class CloudTranscription {
    static JSONObject normalize(JSONObject result)throws Exception{
        if(result.optBoolean("truncated"))throw new IOException("服务返回不完整转写（truncated），请提高输出预算或缩短录音；未覆盖已有原文");
        JSONArray raw=result.optJSONArray("segments"),parts=new JSONArray();if(raw!=null)for(int i=0;i<raw.length();i++){JSONObject original=raw.getJSONObject(i);String text=original.optString("text");boolean ms=original.has("start_ms")&&original.has("end_ms");double start=original.optDouble(ms?"start_ms":"start",Double.NaN),end=original.optDouble(ms?"end_ms":"end",Double.NaN);if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<start)throw new IOException("云端片段缺少有效时间戳；未覆盖已有原文");
            String speaker=original.optString("speaker",original.optString("speaker_id",""));JSONObject part=new JSONObject(original.toString()).put("start_ms",Math.round(start*(ms?1:1000))).put("end_ms",Math.round(end*(ms?1:1000))).put("text",text).put("raw_text",text).put("speaker",speaker.isEmpty()?"身份不确定":"云端说话人 "+speaker+"（仅本次录音）").put("speaker_confirmed",false);parts.put(part);}
        String text=result.optString("text");if(text.isEmpty()&&parts.length()>0){StringBuilder out=new StringBuilder();for(int i=0;i<parts.length();i++){if(out.length()>0)out.append('\n');out.append(parts.getJSONObject(i).optString("text"));}text=out.toString();}
        if(text.trim().isEmpty()&&parts.length()==0)throw new IOException("云端未返回有效转写文字");
        return new JSONObject().put("version",3).put("engine","cloud").put("text",TranscriptDocument.clean(text)).put("raw_text",text).put("segments",parts).put("language",result.optString("language")).put("warnings",result.optJSONArray("warnings")==null?new JSONArray():result.getJSONArray("warnings")).put("duration",result.opt("duration")).put("truncated",false).put("raw_response",result);
    }
    static JSONObject run(ProviderClient provider,JSONObject config,File file,ProviderClient.Cancel cancel,RecordStore db,String job,JSONObject input)throws Exception{
        try{return runInternal(provider,config,file,cancel,db,job,input);}finally{if(cancel.cancelled()&&!input.optString("remote_job_id").isEmpty())try{request(provider,config,"/jobs/"+input.getString("remote_job_id"),"DELETE",null,()->false);}catch(Exception ignored){}}
    }
    private static JSONObject runInternal(ProviderClient provider,JSONObject config,File file,ProviderClient.Cancel cancel,RecordStore db,String job,JSONObject input)throws Exception{
        if(!config.optString("asr_mode","standard").equals("workstation")){JSONObject response=provider.transcribeResult(config,file,cancel);db.update("jobs",job,"result",response.toString());return normalize(response);}
        String remote=input.optString("remote_job_id");if(!remote.isEmpty()&&!remote.matches("[A-Za-z0-9_-]{1,200}"))throw new IOException("已保存的工作站任务ID无效");
        if(remote.isEmpty()){
            String recording=input.optString("remote_recording_id");if(recording.isEmpty()){
                if(file.length()>512L*1024*1024)throw new IOException("工作站单次上传最多 512MB");TaskProgress.set(db,job,"上传整段录音",0,file.length(),"字节");HttpURLConnection c=provider.connection(config,"/uploads/recording",cancel);c.setRequestProperty("Content-Type","application/octet-stream");c.setFixedLengthStreamingMode(file.length());
                try{try(InputStream in=new FileInputStream(file);OutputStream out=c.getOutputStream()){byte[] buffer=new byte[65536];int n;long sent=0;while((n=in.read(buffer))!=-1){if(cancel.cancelled())throw new IOException("已取消上传");out.write(buffer,0,n);sent+=n;TaskProgress.set(db,job,"上传整段录音",sent,file.length(),"字节");}}recording=new JSONObject(provider.result(c,cancel)).getString("id");}finally{c.disconnect();}
                input.put("remote_recording_id",recording);db.update("jobs",job,"input",input.toString());}
            JSONObject payload=new JSONObject().put("recording",recording).put("hotwords",config.optString("asr_prompt"));String capability=config.getString("model");if(capability.equals("asr.default"))payload.put("language",config.optString("asr_language","auto")).put("max_output_tokens",Math.min(2048,Math.max(128,config.optInt("asr_budget",1024))));else if(capability.equals("asr.whisper"))payload.put("language",config.optString("asr_language","auto")).put("beam_size",5).put("word_timestamps",true);else payload.put("max_output_tokens",config.optInt("asr_budget",16384));
            JSONObject created=request(provider,config,"/jobs","POST",new JSONObject().put("capability",capability).put("payload",payload),cancel);remote=created.getString("id");if(!remote.matches("[A-Za-z0-9_-]{1,200}"))throw new IOException("服务任务ID格式无效");input.put("remote_job_id",remote);db.update("jobs",job,"input",input.toString());
        }
        long deadline=android.os.SystemClock.elapsedRealtime()+3L*3600000;
        while(android.os.SystemClock.elapsedRealtime()<deadline){
            if(cancel.cancelled())throw new IOException("已取消本地等待，并尝试取消工作站任务");
            JSONObject state=request(provider,config,"/jobs/"+remote,"GET",null,cancel);String status=state.optString("status",state.optString("state"));JSONObject progress=state.optJSONObject("progress");if(progress!=null)TaskProgress.set(db,job,"工作站 · "+progress.optString("phase",status),Math.round(progress.optDouble("completed_seconds",0)),Math.round(progress.optDouble("total_seconds",0)),"秒");
            if(status.matches("failed|error|cancelled|canceled"))throw new IOException("工作站任务"+status+"；请查看服务端任务，不会自动重传录音");
            if(status.matches("completed|succeeded|success|done")){JSONObject result=state.optJSONObject("result");if(result==null)throw new IOException("工作站任务缺少result");db.update("jobs",job,"result",result.toString());return normalize(result);}
            if(!status.matches("queued|pending|running|processing|loading"))throw new IOException("工作站返回未知任务状态："+status);
            for(int n=0;n<20&&!cancel.cancelled();n++)Thread.sleep(250);
        }throw new IOException("工作站等待超时，任务ID已保存，可重新确认后继续查询");
    }
    private static JSONObject request(ProviderClient provider,JSONObject config,String path,String method,JSONObject body,ProviderClient.Cancel cancel)throws Exception{
        HttpURLConnection c=provider.connection(config,path,cancel);c.setRequestMethod(method);c.setDoOutput(body!=null);if(body!=null){byte[] bytes=body.toString().getBytes("UTF-8");c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
        try{String response=provider.result(c,cancel);return response.trim().isEmpty()?new JSONObject():new JSONObject(response);}finally{c.disconnect();}
    }
}
