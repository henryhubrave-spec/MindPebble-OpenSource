package org.diy.liferecorder;
import android.content.Context;import android.graphics.*;import android.view.View;
/** Small vector symbols, drawn locally at any display density. */
final class PebbleIcon extends View {
 private final String kind;private final Paint p=new Paint(3);private final int color;
 PebbleIcon(Context c,String k,int col){super(c);kind=k;color=col;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
 protected void onDraw(Canvas c){super.onDraw(c);c.save();c.translate(getPaddingLeft(),getPaddingTop());c.scale((getWidth()-getPaddingLeft()-getPaddingRight())/24f,(getHeight()-getPaddingTop()-getPaddingBottom())/24f);p.setColor(color);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStyle(Paint.Style.STROKE);
 switch(kind){
 case "trash":c.drawLine(5,6,19,6,p);c.drawLine(9,3,15,3,p);c.drawRoundRect(7,6,17,21,2,2,p);c.drawLine(10,10,10,17,p);c.drawLine(14,10,14,17,p);break;
 case "copy":c.drawRoundRect(8,7,20,21,2,2,p);c.drawLine(4,17,4,3,p);c.drawLine(4,3,16,3,p);break;
 case "person":c.drawCircle(12,7,4,p);c.drawArc(4,13,20,26,180,180,false,p);c.drawLine(4,20,20,20,p);break;
 case "spark":Path star=new Path();star.moveTo(12,2);star.lineTo(15,9);star.lineTo(22,12);star.lineTo(15,15);star.lineTo(12,22);star.lineTo(9,15);star.lineTo(2,12);star.lineTo(9,9);star.close();c.drawPath(star,p);break;
 case "more":for(int i=0;i<3;i++)c.drawCircle(5+i*7,12,1,p);break;
 case "sun":c.drawCircle(12,12,4,p);for(int n=0;n<8;n++){double a=n*Math.PI/4;c.drawLine(12+(float)Math.cos(a)*8,12+(float)Math.sin(a)*8,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10,p);}break;
 case "wave":for(int n=0;n<5;n++){int h=new int[]{4,8,11,7,3}[n];c.drawLine(4+n*4,12-h,4+n*4,12+h,p);}break;
 case "check":c.drawCircle(12,12,9,p);c.drawLine(7,12,11,16,p);c.drawLine(11,16,17,8,p);break;
 case "tune":for(int n=0;n<3;n++){int y=5+n*7,x=n==1?15:8;c.drawLine(3,y,21,y,p);c.drawRect(x-1,y-3,x+1,y+3,p);}break;
 case "heart":Path h=new Path();h.moveTo(12,21);h.cubicTo(-5,9,3,-3,12,6);h.cubicTo(21,-3,29,9,12,21);c.drawPath(h,p);break;
 case "play":Path t=new Path();t.moveTo(9,6);t.lineTo(18,12);t.lineTo(9,18);t.close();c.drawPath(t,p);break;
 case "moon":c.drawArc(3,3,21,21,60,280,false,p);c.drawArc(9,-1,26,16,85,170,false,p);break;
 case "air":c.drawLine(3,8,17,8,p);c.drawArc(14,3,20,9,180,270,false,p);c.drawLine(3,13,21,13,p);c.drawLine(6,18,15,18,p);c.drawArc(13,17,19,23,180,-270,false,p);break;
 case "back":c.drawLine(15,5,8,12,p);c.drawLine(8,12,15,19,p);break;
 default:c.drawRoundRect(5,4,19,20,4,4,p);c.drawLine(9,1,15,1,p);c.drawLine(9,23,15,23,p);break;
 }c.restore();}
}
