package org.diy.liferecorder;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class LocalLibrary extends SQLiteOpenHelper {
    static final class Audio {
        final String id, title, path;
        final long bytes, durationMs;
        Audio(String id, String title, String path, long bytes, long durationMs) {
            this.id=id; this.title=title; this.path=path; this.bytes=bytes; this.durationMs=durationMs;
        }
    }
    private final Context context;
    private final File directory;
    LocalLibrary(Context context) {
        super(context, "library.db", null, 1);
        this.context=context.getApplicationContext();
        directory=new File(context.getFilesDir(), "audio");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建本地音频目录");
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE audio (id TEXT PRIMARY KEY, title TEXT NOT NULL, path TEXT NOT NULL UNIQUE, bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, duration_ms INTEGER NOT NULL, imported_ms INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("尚无此版本的数据库迁移，请保留原数据");
    }
    List<Audio> list() {
        List<Audio> result=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT id,title,path,bytes,duration_ms FROM audio ORDER BY imported_ms DESC,id", null)) {
            while(c.moveToNext()) result.add(new Audio(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4)));
        }
        return result;
    }
    void importAudio(Uri uri) throws Exception {
        String title="导入的录音";
        try (Cursor c=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(c!=null && c.moveToFirst() && !c.isNull(0)) title=c.getString(0);
        }
        if(title.length()>160) title=title.substring(0,160);
        String id=UUID.randomUUID().toString();
        File partial=new File(directory,id+".part"), complete=new File(directory,id+".audio");
        boolean committed=false;
        try {
            long size=0;
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream source=context.getContentResolver().openInputStream(uri);
                FileOutputStream output=new FileOutputStream(partial)) {
                if(source==null) throw new IllegalArgumentException("文件无法读取");
                byte[] buffer=new byte[65536]; int count;
                while((count=source.read(buffer))!=-1) {
                    size+=count;
                    if(size>512L*1024*1024) throw new IllegalArgumentException("原型单文件最多 512 MB");
                    if(directory.getUsableSpace()<64L*1024*1024) throw new IllegalStateException("手机剩余空间不足");
                    output.write(buffer,0,count); digest.update(buffer,0,count);
                }
                output.getFD().sync();
            }
            if(size==0) throw new IllegalArgumentException("不能导入空文件");
            long duration;
            MediaMetadataRetriever reader=new MediaMetadataRetriever();
            try {
                reader.setDataSource(partial.getAbsolutePath());
                if (!"yes".equals(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)))
                    throw new IllegalArgumentException("文件中没有可识别的音轨");
                duration=Long.parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                if(duration<=0) throw new IllegalArgumentException("音频时长无效");
            } finally { reader.release(); }
            if(!partial.renameTo(complete)) throw new IllegalStateException("音频保存失败");
            StringBuilder hex=new StringBuilder(); for(byte b:digest.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            ContentValues values=new ContentValues();
            values.put("id",id); values.put("title",title); values.put("path",complete.getAbsolutePath());
            values.put("bytes",size); values.put("sha256",hex.toString()); values.put("duration_ms",duration); values.put("imported_ms",System.currentTimeMillis());
            getWritableDatabase().insertOrThrow("audio",null,values);
            committed=true;
        } finally {
            if(partial.exists()) partial.delete();
            if(!committed && complete.exists()) complete.delete();
        }
    }
}
