package org.diy.watchrecorder;
import android.content.*;import android.content.res.*;import java.util.*;import org.json.*;
final class WatchLanguage {
 private static volatile Map<String,String> english;
 static String code(Context c){return c.getSharedPreferences("capture-policy",0).getString("language","zh");}
 static Locale locale(Context c){return code(c).equals("en")?Locale.ENGLISH:Locale.SIMPLIFIED_CHINESE;}
 static String pattern(Context c,String pattern){if(!code(c).equals("en"))return pattern;return pattern.replace("yyyy年 M月","MMMM yyyy").replace("M月d日 EEEE","EEEE, MMM d").replace("M月d日 HH:mm","MMM d HH:mm");}
 static Context wrap(Context c){Configuration config=new Configuration(c.getResources().getConfiguration());config.setLocale(locale(c));return c.createConfigurationContext(config);}
 static void set(Context c,String value){c.getSharedPreferences("app-language",0).edit().putString("code",value.equals("en")?"en":"zh").commit();}
 static CharSequence text(Context c,CharSequence input){if(input==null||!code(c).equals("en"))return input;String value=input.toString();if(english==null)synchronized(WatchLanguage.class){if(english==null){Map<String,String> map=new LinkedHashMap<>();try(java.io.InputStream in=c.getAssets().open("i18n/en.json")){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);JSONObject json=new JSONObject(bytes.toString("UTF-8"));ArrayList<String> keys=new ArrayList<>();json.keys().forEachRemaining(keys::add);keys.sort((a,b)->Integer.compare(b.length(),a.length()));for(String key:keys)map.put(key,json.getString(key));}catch(Exception ignored){}english=map;}}String exact=english.get(value);if(exact!=null)return exact;for(Map.Entry<String,String> e:english.entrySet())if(value.contains(e.getKey()))value=value.replace(e.getKey(),e.getValue());return value;}
 static String text(Context c,String s){return text(c,(CharSequence)s).toString();}
}
