package org.diy.liferecorder;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Button;
import java.io.*;
import java.util.concurrent.*;

/** Explicit backup and preview-before-restore through Android's document picker. */
public final class BackupActivity extends AppScreen {
    private static final int SAVE=811,OPEN=812;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Button save,open;private boolean busy;private BackupArchive.Prepared pending;

    public void onCreate(Bundle saved){
        super.onCreate(saved);frame("完整备份与恢复");button(body,"‹ 返回",this::onBackPressed);
        body.addView(title("完整备份与恢复",25));
        body.addView(text("保存手机中的录音、转写、记忆、待办和回顾。备份不含 API 密钥、配对凭据、声纹模型与声纹登记，也不包含手表尚未同步的录音。ZIP 未加密，请保存在你信任的位置。",14,MUTED));
        save=primary(body,"保存完整备份",()->{
            busy(true);Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"mindpebble-backup.zip");
            try{startActivityForResult(intent,SAVE);}catch(Exception error){busy(false);notice.setText("无法打开文件选择器");}
        });
        body.addView(text("恢复仅支持本版完整备份与空资料库。不会覆盖或合并已有记录；恢复后自动云端处理保持关闭，提醒需重新设置。选择性导出 ZIP 不能用于恢复。",14,MUTED));
        open=button(body,"选择备份并检查",()->{
            busy(true);try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE),OPEN);}catch(Exception error){busy(false);notice.setText("无法打开文件选择器");}
        });
    }
    private void busy(boolean value){busy=value;save.setEnabled(!value);open.setEnabled(!value);}
    protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=SAVE&&request!=OPEN)return;
        if(result!=RESULT_OK||data==null||data.getData()==null){busy(false);return;}
        busy(true);Uri uri=data.getData();notice.setText(request==SAVE?"正在保存完整备份…":"正在检查备份与音频校验值…");
        worker.execute(()->{
            try(RecordStore db=new RecordStore(getApplicationContext())){
                if(request==SAVE){
                    try(OutputStream output=getContentResolver().openOutputStream(uri,"wt")){
                        if(output==null)throw new IOException("无法写入保存位置");BackupArchive.write(db,output);
                    }
                    runOnUiThread(()->{if(!isDestroyed()){busy(false);notice.setText("完整备份已保存");}});
                }else{
                    BackupArchive.Prepared prepared;
                    try(InputStream input=getContentResolver().openInputStream(uri)){
                        if(input==null)throw new IOException("无法读取备份");prepared=BackupArchive.prepare(getCacheDir(),input);
                    }
                    runOnUiThread(()->{
                        if(isDestroyed()){prepared.close();return;}
                        pending=prepared;new LocalDialog(this).setTitle("恢复这份备份？")
                            .setMessage("已校验音频文件，包含 "+prepared.recordings()+" 段录音。仅导入空资料库；不会开启自动云端处理。")
                            .setPositiveButton("恢复",(d,w)->{pending=null;worker.execute(()->{
                                try(BackupArchive.Prepared ignored=prepared;RecordStore target=new RecordStore(getApplicationContext())){
                                    BackupArchive.restore(target,prepared);
                                    runOnUiThread(()->{if(!isDestroyed()){busy(false);notice.setText("恢复完成，可返回查看记录。自动云端处理保持关闭，提醒请重新设置。");}});
                                }catch(Exception error){failed(error);}
                            });})
                            .setNegativeButton("取消",(d,w)->{pending=null;prepared.close();busy(false);notice.setText("已取消恢复");}).show();
                    });
                }
            }catch(Exception error){if(request==SAVE)try{if(DocumentsContract.isDocumentUri(this,uri))DocumentsContract.deleteDocument(getContentResolver(),uri);else getContentResolver().delete(uri,null,null);}catch(Exception ignored){}failed(error);}
        });
    }
    private void failed(Exception error){runOnUiThread(()->{if(!isDestroyed()){busy(false);notice.setText(error instanceof IOException?error.getMessage():"备份处理失败，文件内容或格式不兼容；现有资料未被覆盖。");}});}
    public void onBackPressed(){if(busy){notice.setText("请等待操作完成，或取消当前确认窗口。");return;}super.onBackPressed();}
    public void onDestroy(){if(pending!=null)pending.close();worker.shutdown();super.onDestroy();}
}
