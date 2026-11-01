package org.diy.liferecorder;
/** A per-file byte denominator is known; the session file total is intentionally unknown. */
final class TransferProgress {
 static final class Snapshot {final String file,stage;final long received,total,sessionBytes,updated;final int completed;final boolean active;Snapshot(String file,String stage,long received,long total,long sessionBytes,int completed,boolean active){this.file=file;this.stage=stage;this.received=received;this.total=total;this.sessionBytes=sessionBytes;this.completed=completed;this.active=active;updated=android.os.SystemClock.elapsedRealtime();}String label(){return stage+(file.isEmpty()?"":" · 第 "+(completed+1)+" 个文件")+(total>0?String.format(java.util.Locale.CHINA," · %d%% · %.1f / %.1f MB",Math.min(100,received*100/total),received/1048576.0,total/1048576.0):"")+" · 已完成 "+completed+" 个";}}
 static volatile Snapshot current=new Snapshot("","尚无接收任务",0,0,0,0,false);private static long session;private static int saved;
 static synchronized void begin(){session=0;saved=0;current=new Snapshot("","正在接收手表文件",0,0,0,0,true);}
 static synchronized void update(String file,long received,long total,String stage){current=new Snapshot(file,stage,received,total,session+received,saved,true);}
 static synchronized void saved(long bytes){session+=bytes;saved++;current=new Snapshot("","文件已保存，等待下一份",0,0,session,saved,true);}
 static synchronized void finish(){current=new Snapshot("","接收完成",0,0,session,saved,false);}
 static synchronized void failed(){Snapshot old=current;current=new Snapshot(old.file,"接收已中断，已保存文件保留",old.received,old.total,old.sessionBytes,old.completed,false);}
}
