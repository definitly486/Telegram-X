package org.thunderdog.challegram.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SshTunnelConfig {
  private static final String PREFS = "ssh_tunnel_config";
  private static final String KEY_ALIAS = "telegram_x_ssh_tunnel_key";
  public String host = "";
  public int sshPort = 22;
  public String username = "";
  public String password = "";
  public int remotePort = 48555;
  public String knownHosts = "";

  private SshTunnelConfig () { }

  private static SharedPreferences prefs (Context c) {
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }
  public static boolean isEnabled (Context c) { return prefs(c).getBoolean("enabled", false); }
  public static void setEnabled (Context c, boolean value) { prefs(c).edit().putBoolean("enabled", value).apply(); }

  public static SshTunnelConfig load (Context c) {
    SshTunnelConfig v = new SshTunnelConfig();
    SharedPreferences p = prefs(c);
    v.host = p.getString("host", "");
    v.sshPort = p.getInt("ssh_port", 22);
    v.username = p.getString("username", "");
    v.remotePort = p.getInt("remote_port", 48555);
    v.knownHosts = p.getString("known_hosts", "");
    String raw = p.getString("password", null);
    if (raw != null && Build.VERSION.SDK_INT >= 23) {
      try {
        byte[] all = Base64.decode(raw, Base64.NO_WRAP);
        byte[] iv = new byte[12], body = new byte[all.length - 12];
        System.arraycopy(all, 0, iv, 0, 12);
        System.arraycopy(all, 12, body, 0, body.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        v.password = new String(cipher.doFinal(body), StandardCharsets.UTF_8);
      } catch (Throwable ignored) { v.password = ""; }
    }
    return v;
  }

  public static boolean save (Context c, String host, int sshPort, String username,
                              String password, int remotePort, String knownHosts) {
    if (Build.VERSION.SDK_INT < 23) return false;
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key());
      byte[] body = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
      byte[] iv = cipher.getIV(), all = new byte[iv.length + body.length];
      System.arraycopy(iv, 0, all, 0, iv.length);
      System.arraycopy(body, 0, all, iv.length, body.length);
      prefs(c).edit().putString("host", host).putInt("ssh_port", sshPort)
        .putString("username", username)
        .putString("password", Base64.encodeToString(all, Base64.NO_WRAP))
        .putInt("remote_port", remotePort).putString("known_hosts", knownHosts)
        .putBoolean("enabled", true).apply();
      return true;
    } catch (Throwable e) { return false; }
  }

  private static SecretKey key () throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setRandomizedEncryptionRequired(true).build());
    return generator.generateKey();
  }
}
