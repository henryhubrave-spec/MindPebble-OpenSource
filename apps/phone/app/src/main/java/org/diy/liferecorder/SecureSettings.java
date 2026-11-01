package org.diy.liferecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keys are encrypted on disk; audio/database encryption is a separate feature. */
final class SecureSettings {
    private static final String ALIAS = "life-recorder-provider-keys-v1";
    private final SharedPreferences prefs;

    SecureSettings(Context context) {
        prefs = context.getSharedPreferences("local-settings-v1", Context.MODE_PRIVATE);
    }

    boolean vadRequested() { return prefs.getBoolean("vad-requested", false); }
    void setVadRequested(boolean value) {
        if (!prefs.edit().putBoolean("vad-requested", value).commit())
            throw new IllegalStateException("设置保存失败，请重试");
    }
    String endpoint(String role) { return prefs.getString(role + "-endpoint", ""); }
    String model(String role) { return prefs.getString(role + "-model", ""); }
    boolean hasKey(String role) { return prefs.contains(role + "-key"); }
    long revision(String role) { return prefs.getLong(role + "-revision", 0); }

    void saveProvider(String role, String endpoint, String model, String replacementKey, boolean clearKey) throws Exception {
        String address = ProviderAddress.validate(endpoint);
        if (model.trim().length() > 200) throw new IllegalArgumentException("模型名称过长");
        SharedPreferences.Editor editor = prefs.edit().putString(role + "-endpoint", address)
                .putString(role + "-model", model.trim()).putLong(role + "-revision", revision(role) + 1);
        if (clearKey) editor.remove(role + "-key");
        else if (!replacementKey.isEmpty()) editor.putString(role + "-key", encrypt(replacementKey));
        else if (!sameOrigin(endpoint(role), address)) editor.remove(role + "-key");
        if (!editor.commit()) throw new IllegalStateException("配置保存失败");
    }

    String asrMode(){return prefs.getString("asr-mode","standard");}
    String asrLanguage(){return prefs.getString("asr-language","auto");}
    String asrPrompt(){return prefs.getString("asr-prompt","");}
    int asrBudget(){return prefs.getInt("asr-budget",16384);}
    void saveAsr(String endpoint,String model,String key,String mode,String language,String prompt,int budget)throws Exception{
        if(!mode.matches("standard|workstation")||!language.matches("auto|zh|en|ja|ko|yue")||prompt.length()>1000||budget<128||budget>32768)throw new IllegalArgumentException("转写参数无效");
        if(mode.equals("workstation")&&!model.matches("asr\\.(default|whisper|moss|meeting)"))throw new IllegalArgumentException("请选择工作站支持的 ASR 能力");
        if(mode.equals("workstation")&&model.equals("asr.default")&&budget>2048)throw new IllegalArgumentException("Qwen 每块输出预算应为128–2048");
        String address=ProviderAddress.validate(endpoint).replaceFirst("/audio/transcriptions/?$","");
        saveProvider("asr",address,model,key,false);
        if(!prefs.edit().putString("asr-mode",mode).putString("asr-language",language).putString("asr-prompt",prompt).putInt("asr-budget",budget).commit())throw new IllegalStateException("转写参数保存失败");
    }
    private boolean sameOrigin(String first, String second) throws Exception {
        if (first.isEmpty() || second.isEmpty()) return first.equals(second);
        java.net.URI a = new java.net.URI(first), b = new java.net.URI(second);
        int aPort = a.getPort() == -1 ? (a.getScheme().equalsIgnoreCase("https") ? 443 : 80) : a.getPort();
        int bPort = b.getPort() == -1 ? (b.getScheme().equalsIgnoreCase("https") ? 443 : 80) : b.getPort();
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost()) && aPort == bPort;
    }

    // Read only when a user-confirmed provider job is about to run.
    String readKey(String role) throws Exception {
        String stored = prefs.getString(role + "-key", null);
        if (stored == null) return "";
        String[] parts = stored.split(":");
        if (parts.length != 2) throw new IllegalStateException("密钥无法读取，请重新填写");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
