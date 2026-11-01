package org.diy.liferecorder;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/** Run only in a disposable emulator. Uses synthetic data and clears its own settings. */
public final class PhoneSmokeTests extends Instrumentation {
    private final ArrayList<String> checks=new ArrayList<>();
    private void check(String name,boolean value) {
        if(!value) throw new AssertionError(name);
        checks.add(name);
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        if(!android.os.Build.HARDWARE.equals("ranchu") && !android.os.Build.HARDWARE.equals("goldfish")) {
            result.putString("error","Disposable emulator required");finish(1,result);return;
        }
        try {
            Context context=getTargetContext();
            check("no INTERNET permission",context.checkCallingOrSelfPermission("android.permission.INTERNET")!=PackageManager.PERMISSION_GRANTED);
            context.getSharedPreferences("local-settings-v1",0).edit().clear().commit();
            SecureSettings settings=new SecureSettings(context);
            check("VAD defaults off",!settings.vadRequested());
            settings.setVadRequested(true);
            check("VAD on persisted",new SecureSettings(context).vadRequested());
            settings.setVadRequested(false);
            check("VAD off persisted",!new SecureSettings(context).vadRequested());
            settings.saveProvider("asr","https://asr.example.invalid/v1","synthetic-asr","synthetic-asr-key",false);
            settings.saveProvider("llm","https://llm.example.invalid/v1","synthetic-llm","synthetic-llm-key",false);
            check("ASR encrypted roundtrip",new SecureSettings(context).readKey("asr").equals("synthetic-asr-key"));
            check("LLM credentials separate",settings.readKey("llm").equals("synthetic-llm-key"));
            check("key absent from plaintext preferences",!context.getSharedPreferences("local-settings-v1",0).getAll().toString().contains("synthetic-asr-key"));
            settings.saveProvider("asr",settings.endpoint("asr"),"replacement-model","",false);
            check("blank key retains",settings.readKey("asr").equals("synthetic-asr-key"));
            settings.saveProvider("asr",settings.endpoint("asr"),"replacement-model","",true);
            check("explicit key clear",!settings.hasKey("asr") && settings.readKey("asr").isEmpty());
            check("clearing ASR preserves LLM",settings.hasKey("llm"));
            settings.saveProvider("llm","https://different.example.invalid/v1","synthetic-llm","",false);
            check("origin change does not inherit key",!settings.hasKey("llm"));
            settings.saveProvider("llm","https://different.example.invalid/v1","synthetic-llm","replacement-test-key",false);
            settings.saveProvider("llm","https://different.example.invalid/another-path","synthetic-llm","",false);
            check("same origin retains key",settings.readKey("llm").equals("replacement-test-key"));
            for(String address:new String[]{"file:///etc/passwd","https://user:key@example.invalid","https://example.invalid?key=bad","https://example.invalid/#key","https://example.invalid:99999","https:///missing-host"}) {
                boolean rejected=false;
                try {ProviderAddress.validate(address);}catch(IllegalArgumentException e){rejected=true;}
                check("invalid address rejected "+checks.size(),rejected);
            }
            check("localhost address config accepted",ProviderAddress.validate("http://127.0.0.1:8000/v1").equals("http://127.0.0.1:8000/v1"));
            LocalLibrary library=new LocalLibrary(context);
            int before=library.list().size();
            File source=new File(context.getCacheDir(),"synthetic-one-second.wav");
            byte[] data=wav();
            try(FileOutputStream output=new FileOutputStream(source)){output.write(data);}
            library.importAudio(Uri.fromFile(source));
            check("synthetic audio imported",library.list().size()==before+1);
            LocalLibrary.Audio audio=library.list().get(0);
            check("one second duration",audio.durationMs>=990 && audio.durationMs<=1010);
            check("source bytes preserved",source.length()==data.length && audio.bytes==data.length);
            check("app private copy exists",new File(audio.path).isFile() && !audio.path.equals(source.getAbsolutePath()));
            android.media.MediaPlayer player=new android.media.MediaPlayer();
            try {
                player.setDataSource(audio.path); player.prepare();
                check("imported audio decodes for playback",player.getDuration()>=990 && player.getDuration()<=1010);
            } finally {player.release();}
            File empty=new File(context.getCacheDir(),"synthetic-empty.wav");empty.createNewFile();
            boolean rejected=false;try{library.importAudio(Uri.fromFile(empty));}catch(Exception e){rejected=true;}
            check("empty import rejected",rejected && library.list().size()==before+1);
            check("failed import removes partial files",new File(context.getFilesDir(),"audio").listFiles((dir,name)->name.endsWith(".part")).length==0);
            File invalid=new File(context.getCacheDir(),"synthetic-invalid.wav");
            try(FileOutputStream output=new FileOutputStream(invalid)){output.write(new byte[]{1,2,3,4,5,6});}
            rejected=false;try{library.importAudio(Uri.fromFile(invalid));}catch(Exception e){rejected=true;}
            check("non-audio import rejected",rejected && library.list().size()==before+1);
            check("invalid media leaves no partial files",new File(context.getFilesDir(),"audio").listFiles((dir,name)->name.endsWith(".part")).length==0);
            context.getSharedPreferences("local-settings-v1",0).edit().clear().commit();
            result.putStringArrayList("passed",checks);result.putInt("count",checks.size());finish(-1,result);
        } catch(Throwable error) {
            result.putStringArrayList("passed",checks);result.putString("error",error.getClass().getSimpleName()+": "+error.getMessage());finish(1,result);
        }
    }
    private byte[] wav() {
        int samples=16000;
        ByteBuffer b=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(36+samples*2).put(new byte[]{'W','A','V','E','f','m','t',' '});
        b.putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16);
        b.put(new byte[]{'d','a','t','a'}).putInt(samples*2);
        for(int i=0;i<samples;i++)b.putShort((short)(2000*Math.sin(2*Math.PI*440*i/16000)));
        return b.array();
    }
}
