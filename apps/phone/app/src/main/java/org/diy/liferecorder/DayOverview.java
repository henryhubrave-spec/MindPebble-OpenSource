package org.diy.liferecorder;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import org.json.*;
import java.util.*;

/** Only observed recording coverage. Empty hours never imply a recording failure. */
final class DayOverview {
    final long[] hours=new long[24];
    long recordedMs; int recordings,marks,pending;
    static long start(long time){Calendar c=Calendar.getInstance();c.setTimeInMillis(time);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
    static long next(long day){Calendar c=Calendar.getInstance();c.setTimeInMillis(day);c.add(Calendar.DAY_OF_MONTH,1);return c.getTimeInMillis();}
    static DayOverview from(JSONArray rows,long day)throws JSONException{
        DayOverview result=new DayOverview();long end=next(day);ArrayList<long[]> intervals=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONObject a=rows.getJSONObject(i);long at=a.optLong("created"),duration=Math.max(0,a.optLong("duration"));
            long stop=at>Long.MAX_VALUE-duration?Long.MAX_VALUE:at+duration;
            if(at>=end||stop<=day)continue;
            result.recordings++;if(a.optString("transcript").isEmpty()&&a.optInt("transcribed")==0)result.pending++;
            intervals.add(new long[]{Math.max(day,at),Math.min(end,stop)});
            JSONArray markers=AudioReview.object(a.optString("metadata")).optJSONArray("markers");
            if(markers!=null)for(int j=0;j<markers.length();j++){JSONObject m=markers.optJSONObject(j);if(m==null)continue;long offset=m.optLong("offset_ms",-1);if(offset>=0&&offset<duration&&at+offset>=day&&at+offset<end)result.marks++;}
        }
        intervals.sort((a,b)->Long.compare(a[0],b[0]));ArrayList<long[]> merged=new ArrayList<>();
        for(long[] interval:intervals){if(merged.isEmpty()||interval[0]>merged.get(merged.size()-1)[1])merged.add(interval.clone());else merged.get(merged.size()-1)[1]=Math.max(merged.get(merged.size()-1)[1],interval[1]);}
        for(long[] interval:merged){result.recordedMs+=interval[1]-interval[0];long at=interval[0];while(at<interval[1]){Calendar c=Calendar.getInstance();c.setTimeInMillis(at);int h=c.get(Calendar.HOUR_OF_DAY);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);c.add(Calendar.HOUR_OF_DAY,1);long stop=Math.min(interval[1],c.getTimeInMillis());if(stop<=at)break;result.hours[h]+=stop-at;at=stop;}}
        return result;
    }
    static String duration(long ms){long minutes=ms/60000;return minutes>=60?minutes/60+" 小时 "+minutes%60+" 分钟":minutes>0?minutes+" 分钟":ms>0?"不到 1 分钟":"0 分钟";}
    static final class Chart extends View{
        final AppScreen screen;final DayOverview data;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Chart(AppScreen screen,DayOverview data){super(screen);this.screen=screen;this.data=data;setContentDescription("一天的录音覆盖，"+duration(data.recordedMs)+"，空白时段表示手机尚无录音");setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
        protected void onDraw(Canvas c){super.onDraw(c);float width=getWidth()/24f,base=getHeight()-screen.dp(24);for(int i=0;i<24;i++){p.setColor(screen.TINT);c.drawRoundRect(i*width+2,screen.dp(6),(i+1)*width-2,base,screen.dp(3),screen.dp(3),p);if(data.hours[i]>0){p.setColor(screen.SAGE);float h=Math.max(screen.dp(4),(base-screen.dp(6))*Math.min(1,data.hours[i]/3600000f));c.drawRoundRect(i*width+2,base-h,(i+1)*width-2,base,screen.dp(3),screen.dp(3),p);}}p.setColor(screen.MUTED);p.setTextSize(screen.dp(10));for(int h:new int[]{0,6,12,18})c.drawText(String.format(Locale.ROOT,"%02d",h),h*width,getHeight()-screen.dp(4),p);p.setTextAlign(Paint.Align.RIGHT);c.drawText("24",getWidth(),getHeight()-screen.dp(4),p);p.setTextAlign(Paint.Align.LEFT);}
    }
}
