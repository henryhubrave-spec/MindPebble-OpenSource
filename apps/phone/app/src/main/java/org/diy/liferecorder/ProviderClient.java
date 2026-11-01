package org.diy.liferecorder;

import java.net.*;
import java.io.*;
import org.json.*;

/** Only outbound provider boundary. No redirects, telemetry or implicit uploads. */
final class ProviderClient {
    interface Cancel { boolean cancelled(); }
    private final SecureSettings settings;
    private final android.content.Context context;
    ProviderClient(android.content.Context c){context=c;settings=new SecureSettings(c);}
    JSONObject snapshot(String role)throws Exception{
        return new JSONObject().put("language",AppLanguage.code(context)).put("role",role).put("endpoint",settings.endpoint(role)).put("model",settings.model(role))
          .put("strict_json",context.getSharedPreferences("provider-json",0).getBoolean("strict",false)).put("asr_mode",settings.asrMode()).put("asr_language",settings.asrLanguage()).put("asr_prompt",settings.asrPrompt()).put("asr_budget",settings.asrBudget()).put("temperature",context.getSharedPreferences("provider-tone",0).getFloat("temperature",.7f)).put("revision",settings.revision(role)).put("allow_http",context.getSharedPreferences("network-policy",0).getBoolean("allow_local_http",false));
    }
    HttpURLConnection connection(JSONObject config,String path,Cancel cancel)throws Exception{
        String role=config.getString("role");
        if(settings.revision(role)!=config.optLong("revision",-1)||!settings.endpoint(role).equals(config.getString("endpoint"))||!settings.model(role).equals(config.getString("model")))throw new IOException("API 配置已改变，请重新确认请求");
        String base=ProviderAddress.validate(config.getString("endpoint"));if(base.isEmpty()||config.getString("model").isEmpty())throw new IOException("请先填写 API 地址与模型");
        URL url=new URL(base.endsWith(path)?base:base.replaceAll("/+$","")+path);
        if(url.getProtocol().equals("http")){
            if(!config.optBoolean("allow_http")||!context.getSharedPreferences("network-policy",0).getBoolean("allow_local_http",false))throw new IOException("本地 HTTP 未获允许，请使用 HTTPS 或在设置明确开启");
            for(InetAddress ip:InetAddress.getAllByName(url.getHost())){
                byte[] bytes=ip.getAddress();boolean unique=bytes.length==16&&((bytes[0]&254)==252);
                if(!(ip.isSiteLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||unique))throw new IOException("HTTP 仅允许局域网地址");
            }
        }
        if(cancel.cancelled())throw new IOException("已取消");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(180000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Accept","application/json");
        String key=settings.readKey(role);if(!key.isEmpty())c.setRequestProperty("Authorization","Bearer "+key);return c;
    }
    String result(HttpURLConnection c,Cancel cancel)throws Exception{
        int status=c.getResponseCode();if(status<200||status>=300)throw new IOException("模型服务返回 HTTP "+status+"；未自动重试");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(cancel.cancelled())throw new IOException("已取消");if(bytes.size()+n>8*1024*1024)throw new IOException("模型响应过大");bytes.write(b,0,n);}}
        return bytes.toString("UTF-8");
    }
    String transcribe(JSONObject config,File audio,Cancel cancel)throws Exception{return transcribeResult(config,audio,cancel).getString("text");}
    JSONObject transcribeResult(JSONObject config,File audio,Cancel cancel)throws Exception{
        if(!audio.isFile()||audio.length()==0)throw new IOException("没有可转写的音频，请保留或导入音频");
        long maxBytes=(config.optString("model").startsWith("asr.")?512L:25L)*1024*1024;if(audio.length()>maxBytes)throw new IOException("音频超过当前接口上传上限，请使用工作站异步转写或缩短录音");
        String extension="m4a",mime="audio/mp4";byte[] header=new byte[12];int read;try(InputStream inspect=new FileInputStream(audio)){read=inspect.read(header);}
        String magic=new String(header,0,Math.max(0,read),"ISO-8859-1");
        if(magic.startsWith("RIFF")&&magic.endsWith("WAVE")){extension="wav";mime="audio/wav";}
        else if(magic.startsWith("OggS")){extension="ogg";mime="audio/ogg";}
        else if(magic.startsWith("fLaC")){extension="flac";mime="audio/flac";}
        else if(magic.startsWith("ID3")||(read>1&&(header[0]&255)==255&&(header[1]&224)==224)){extension="mp3";mime="audio/mpeg";}
        HttpURLConnection c=connection(config,"/audio/transcriptions",cancel);String boundary="LR"+java.util.UUID.randomUUID().toString().replace("-","");
        c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);c.setChunkedStreamingMode(65536);
        try{
            try(OutputStream out=c.getOutputStream()){
                field(out,boundary,"response_format","verbose_json");field(out,boundary,"language",config.optString("asr_language","auto"));if(!config.optString("asr_prompt").isEmpty())field(out,boundary,"prompt",config.optString("asr_prompt"));
                out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"+config.getString("model")+"\r\n").getBytes("UTF-8"));
                out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording."+extension+"\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes("UTF-8"));
                try(InputStream in=new FileInputStream(audio)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(cancel.cancelled())throw new IOException("已取消");out.write(b,0,n);}}
                out.write(("\r\n--"+boundary+"--\r\n").getBytes("UTF-8"));
            }
            String response=result(c,cancel);return new JSONObject(response);
        }finally{c.disconnect();}
    }
    private static void field(OutputStream out,String boundary,String name,String value)throws IOException{out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n").getBytes("UTF-8"));}
    String chat(JSONObject config,String system,String content,Cancel cancel)throws Exception{return chatInternal(config,system,content,null,cancel);}
    String chatStructured(JSONObject config,String system,String content,JSONObject schema,Cancel cancel)throws Exception{return chatInternal(config,system,content,config.optBoolean("strict_json")?schema:null,cancel);}
    private String chatInternal(JSONObject config,String system,String content,JSONObject schema,Cancel cancel)throws Exception{
        system += config.optString("language",AppLanguage.code(context)).equals("en")?"\nWrite all natural-language values and replies in English. Keep the required JSON field names unchanged.":"\n所有自然语言内容与回复使用简体中文，保留要求的 JSON 字段名。";
        if(content.length()>60000)throw new IOException("所选文字过多，请减少来源或分段分析");
        if(new URI(config.getString("endpoint")).getHost().equalsIgnoreCase("api.anthropic.com")){if(schema!=null)throw new IOException("原生 Claude 接口请关闭严格 JSON Schema，仍会执行本地证据校验");return claude(config,system,content,cancel);}
        HttpURLConnection c=connection(config,"/chat/completions",cancel);
        JSONObject request=new JSONObject().put("model",config.getString("model")).put("temperature",Math.max(0,Math.min(1.2,config.optDouble("temperature",.7)))).put("stream",false).put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",system)).put(new JSONObject().put("role","user").put("content",content)));if(schema!=null)request.put("response_format",new JSONObject().put("type","json_schema").put("json_schema",new JSONObject().put("name","life_record").put("strict",true).put("schema",schema)));byte[] body=request.toString().getBytes("UTF-8");
        c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(body.length);
        try{try(OutputStream out=c.getOutputStream()){out.write(body);}return new JSONObject(result(c,cancel)).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");}finally{c.disconnect();}
    }
    private String claude(JSONObject config,String system,String content,Cancel cancel)throws Exception{
        HttpURLConnection c=connection(config,"/messages",cancel);c.setRequestProperty("Authorization","");c.setRequestProperty("x-api-key",settings.readKey(config.getString("role")));c.setRequestProperty("anthropic-version","2023-06-01");c.setRequestProperty("Content-Type","application/json");byte[] body=new JSONObject().put("model",config.getString("model")).put("max_tokens",4096).put("temperature",Math.min(1,config.optDouble("temperature",.7))).put("system",system).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content",content))).toString().getBytes("UTF-8");c.setFixedLengthStreamingMode(body.length);try{try(OutputStream out=c.getOutputStream()){out.write(body);}JSONArray parts=new JSONObject(result(c,cancel)).getJSONArray("content");StringBuilder answer=new StringBuilder();for(int n=0;n<parts.length();n++)if(parts.getJSONObject(n).optString("type").equals("text"))answer.append(parts.getJSONObject(n).optString("text"));return answer.toString();}finally{c.disconnect();}
    }
    long ping(String role)throws Exception{JSONObject config=snapshot(role);long start=android.os.SystemClock.elapsedRealtime();if(new URI(config.getString("endpoint")).getHost().equalsIgnoreCase("api.anthropic.com")){claude(config,"Reply briefly.","Reply OK.",()->false);return android.os.SystemClock.elapsedRealtime()-start;}HttpURLConnection c=connection(config,"/models",()->false);c.setRequestMethod("GET");c.setDoOutput(false);c.setReadTimeout(15000);try{int status=c.getResponseCode();if(status<200||status>=300)throw new IOException("服务返回 HTTP "+status+"；请核对地址、密钥及服务是否支持 /models");return android.os.SystemClock.elapsedRealtime()-start;}finally{c.disconnect();}}

}
