package org.diy.lifecore;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.io.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Device-local secrets, content hashes and durable directory commits. */
public final class LocalSecurity {
    private static final String AES="liferecord-secrets-v2";
    public static String randomToken() {byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.encodeToString(b,Base64.NO_WRAP|Base64.URL_SAFE);}
    private static KeyStore store() throws Exception {KeyStore k=KeyStore.getInstance("AndroidKeyStore");k.load(null);return k;}
    private static synchronized SecretKey secret() throws Exception {
        KeyStore k=store();if(k.containsAlias(AES))return (SecretKey)k.getKey(AES,null);
        KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(AES,3).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build());return g.generateKey();
    }
    public static String seal(String s) throws Exception {Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(1,secret());return Base64.encodeToString(c.getIV(),2)+":"+Base64.encodeToString(c.doFinal(s.getBytes("UTF-8")),2);}
    public static String open(String s) throws Exception {String[] p=s.split(":");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(2,secret(),new GCMParameterSpec(128,Base64.decode(p[0],2)));return new String(c.doFinal(Base64.decode(p[1],2)),"UTF-8");}
    public static String hex(byte[] data) {StringBuilder b=new StringBuilder();for(byte v:data)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
    public static String hash(File f) throws Exception {MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream i=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=i.read(b))!=-1)d.update(b,0,n);}return hex(d.digest());}
    public static boolean equalsSecret(String a,String b) throws Exception{return MessageDigest.isEqual(a.getBytes("UTF-8"),b.getBytes("UTF-8"));}
    public static void syncDirectory(File dir)throws Exception {java.io.FileDescriptor fd=android.system.Os.open(dir.getPath(),android.system.OsConstants.O_RDONLY,0);try{android.system.Os.fsync(fd);}finally{android.system.Os.close(fd);}}
}
