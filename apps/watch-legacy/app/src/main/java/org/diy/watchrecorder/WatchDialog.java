package org.diy.watchrecorder;
import android.app.*;import android.content.*;
/** Shared flat dialog with explicit app language, including buttons and choice lists. */
final class WatchDialog extends AlertDialog.Builder {
 private final Context context;WatchDialog(Context c){super(c);context=c;}
 private CharSequence t(CharSequence s){return WatchLanguage.text(context,s);}
 public WatchDialog setTitle(CharSequence s){super.setTitle(t(s));return this;}public WatchDialog setMessage(CharSequence s){super.setMessage(t(s));return this;}
 public WatchDialog setItems(CharSequence[] values,DialogInterface.OnClickListener click){CharSequence[] copy=new CharSequence[values.length];for(int i=0;i<values.length;i++)copy[i]=t(values[i]);super.setItems(copy,click);return this;}
 public WatchDialog setPositiveButton(CharSequence s,DialogInterface.OnClickListener click){super.setPositiveButton(t(s),click);return this;}public WatchDialog setNegativeButton(CharSequence s,DialogInterface.OnClickListener click){super.setNegativeButton(t(s),click);return this;}public WatchDialog setNeutralButton(CharSequence s,DialogInterface.OnClickListener click){super.setNeutralButton(t(s),click);return this;}
}
