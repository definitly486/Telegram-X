/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.backup;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.service.SshTunnelConfig;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Password-protected export/import of Telegram X proxy configurations. */
public final class ProxyBackup {
  private static final byte[] MAGIC = {'T', 'G', 'X', 'P'};
  private static final int VERSION = 1;
  private static final int SALT_SIZE = 16;
  private static final int NONCE_SIZE = 12;
  private static final int KEY_SIZE = 256;
  private static final int ITERATIONS = 210_000;
  private static final int MAX_FILE_SIZE = 1024 * 1024;
  private static final int MAX_PROXIES = 500;

  public static final String FILE_EXTENSION = "tgxproxy";

  public static final class BadPasswordException extends IOException {
    public BadPasswordException() { super("Wrong password or damaged proxy backup"); }
  }

  private ProxyBackup() { }

  public static boolean export(List<Settings.Proxy> proxies, OutputStream output, char[] password,
                               SshTunnelConfig sshTunnel, boolean sshEnabled)
      throws IOException, GeneralSecurityException, JSONException {
    JSONArray array = new JSONArray();
    for (Settings.Proxy item : proxies) {
      TdApi.InternalLinkTypeProxy proxy = item.proxy;
      if (proxy == null) continue;
      JSONObject json = new JSONObject();
      json.put("server", proxy.server);
      json.put("port", proxy.port);
      json.put("description", item.description == null ? JSONObject.NULL : item.description);
      switch (proxy.type.getConstructor()) {
        case TdApi.ProxyTypeSocks5.CONSTRUCTOR: {
          TdApi.ProxyTypeSocks5 type = (TdApi.ProxyTypeSocks5) proxy.type;
          json.put("type", "socks5");
          json.put("username", type.username);
          json.put("password", type.password);
          break;
        }
        case TdApi.ProxyTypeMtproto.CONSTRUCTOR: {
          TdApi.ProxyTypeMtproto type = (TdApi.ProxyTypeMtproto) proxy.type;
          json.put("type", "mtproto");
          json.put("secret", type.secret);
          break;
        }
        case TdApi.ProxyTypeHttp.CONSTRUCTOR: {
          TdApi.ProxyTypeHttp type = (TdApi.ProxyTypeHttp) proxy.type;
          json.put("type", "http");
          json.put("username", type.username);
          json.put("password", type.password);
          json.put("httpOnly", type.httpOnly);
          break;
        }
        default:
          throw new IOException("Unsupported proxy type");
      }
      array.put(json);
    }
    if (array.length() == 0) throw new IOException("No proxy configurations to export");
    JSONObject root = new JSONObject();
    root.put("format", "telegram-x-proxies");
    root.put("version", VERSION);
    root.put("proxies", array);
    boolean hasSshSettings = sshTunnel != null && !sshTunnel.host.trim().isEmpty();
    if (sshEnabled && !hasSshSettings) {
      throw new IOException("SSH tunnel is enabled, but its configuration was not found. Open SSH tunnel settings and save them again.");
    }
    if (hasSshSettings) {
      if (sshTunnel.username.trim().isEmpty() || sshTunnel.password.isEmpty()
          || sshTunnel.knownHosts.trim().isEmpty()
          || sshTunnel.sshPort < 1 || sshTunnel.sshPort > 65535
          || sshTunnel.remotePort < 1 || sshTunnel.remotePort > 65535) {
        throw new IOException("SSH tunnel settings are incomplete and cannot be exported. Re-enter and save the SSH settings.");
      }
      JSONObject ssh = new JSONObject();
      ssh.put("host", sshTunnel.host);
      ssh.put("sshPort", sshTunnel.sshPort);
      ssh.put("username", sshTunnel.username);
      ssh.put("password", sshTunnel.password);
      ssh.put("remotePort", sshTunnel.remotePort);
      ssh.put("knownHosts", sshTunnel.knownHosts);
      ssh.put("enabled", sshEnabled);
      root.put("sshTunnel", ssh);
    }
    byte[] plain = root.toString().getBytes(StandardCharsets.UTF_8);

    SecureRandom random = new SecureRandom();
    byte[] salt = new byte[SALT_SIZE];
    byte[] nonce = new byte[NONCE_SIZE];
    random.nextBytes(salt);
    random.nextBytes(nonce);
    byte[] key = deriveKey(password, salt);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
    cipher.updateAAD(header(salt, nonce));
    byte[] encrypted = cipher.doFinal(plain);
    output.write(header(salt, nonce));
    output.write(encrypted);
    output.flush();
    java.util.Arrays.fill(key, (byte) 0);
    java.util.Arrays.fill(plain, (byte) 0);
    return hasSshSettings;
  }

  public static ImportedBackup importBackup(InputStream input, char[] password)
      throws IOException, GeneralSecurityException, JSONException {
    byte[] file = readLimited(input);
    int headerSize = MAGIC.length + 1 + SALT_SIZE + NONCE_SIZE;
    if (file.length <= headerSize) throw new IOException("Invalid or empty proxy backup");
    for (int i = 0; i < MAGIC.length; i++) {
      if (file[i] != MAGIC[i]) throw new IOException("This is not a Telegram X proxy backup");
    }
    if ((file[MAGIC.length] & 0xff) != VERSION) throw new IOException("Unsupported backup version");
    byte[] salt = java.util.Arrays.copyOfRange(file, MAGIC.length + 1, MAGIC.length + 1 + SALT_SIZE);
    byte[] nonce = java.util.Arrays.copyOfRange(file, MAGIC.length + 1 + SALT_SIZE, headerSize);
    byte[] encrypted = java.util.Arrays.copyOfRange(file, headerSize, file.length);
    byte[] key = deriveKey(password, salt);
    byte[] plain;
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(java.util.Arrays.copyOf(file, headerSize));
      plain = cipher.doFinal(encrypted);
    } catch (AEADBadTagException e) {
      throw new BadPasswordException();
    } finally {
      java.util.Arrays.fill(key, (byte) 0);
    }

    JSONObject root = new JSONObject(new String(plain, StandardCharsets.UTF_8));
    java.util.Arrays.fill(plain, (byte) 0);
    if (!"telegram-x-proxies".equals(root.optString("format")) || root.optInt("version") != VERSION)
      throw new IOException("Unsupported proxy backup format");
    JSONArray array = root.getJSONArray("proxies");
    if (array.length() == 0 || array.length() > MAX_PROXIES) throw new IOException("Invalid proxy count");
    JSONObject sshJson = root.optJSONObject("sshTunnel");
    ImportedSshTunnel sshTunnel = null;
    if (sshJson != null) {
      String host = sshJson.optString("host", "");
      int sshPort = sshJson.optInt("sshPort", 22);
      String username = sshJson.optString("username", "");
      String sshPassword = sshJson.optString("password", "");
      int remotePort = sshJson.optInt("remotePort", 48555);
      String knownHosts = sshJson.optString("knownHosts", "");
      if (host.trim().isEmpty() || username.trim().isEmpty() || sshPassword.isEmpty()
          || knownHosts.trim().isEmpty() || sshPort < 1 || sshPort > 65535
          || remotePort < 1 || remotePort > 65535) {
        throw new IOException("Invalid SSH tunnel settings in backup");
      }
      sshTunnel = new ImportedSshTunnel(host, sshPort, username, sshPassword,
        remotePort, knownHosts, sshJson.optBoolean("enabled", true));
    }
    List<ImportedProxy> result = new ArrayList<>(array.length());
    for (int i = 0; i < array.length(); i++) {
      JSONObject json = array.getJSONObject(i);
      String server = json.getString("server");
      int port = json.getInt("port");
      if (server.trim().isEmpty() || port < 1 || port > 65535) throw new IOException("Invalid proxy address");
      String typeName = json.getString("type");
      TdApi.ProxyType type;
      switch (typeName) {
        case "socks5":
          type = new TdApi.ProxyTypeSocks5(json.optString("username", ""), json.optString("password", ""));
          break;
        case "mtproto":
          type = new TdApi.ProxyTypeMtproto(json.getString("secret"));
          break;
        case "http":
          type = new TdApi.ProxyTypeHttp(json.optString("username", ""), json.optString("password", ""), json.optBoolean("httpOnly", true));
          break;
        default:
          throw new IOException("Unknown proxy type: " + typeName);
      }
      Object descriptionValue = json.opt("description");
      String description = descriptionValue == null || descriptionValue == JSONObject.NULL ? null : String.valueOf(descriptionValue);
      result.add(new ImportedProxy(new TdApi.InternalLinkTypeProxy(server, port, type), description));
    }
    return new ImportedBackup(result, sshTunnel);
  }

  public static List<ImportedProxy> importProxies(InputStream input, char[] password)
      throws IOException, GeneralSecurityException, JSONException {
    return importBackup(input, password).proxies;
  }

  private static byte[] readLimited(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int total = 0, read;
    while ((read = input.read(buffer)) != -1) {
      total += read;
      if (total > MAX_FILE_SIZE) throw new IOException("Proxy backup is too large");
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static byte[] header(byte[] salt, byte[] nonce) {
    byte[] header = new byte[MAGIC.length + 1 + SALT_SIZE + NONCE_SIZE];
    System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
    header[MAGIC.length] = VERSION;
    System.arraycopy(salt, 0, header, MAGIC.length + 1, SALT_SIZE);
    System.arraycopy(nonce, 0, header, MAGIC.length + 1 + SALT_SIZE, NONCE_SIZE);
    return header;
  }

  private static byte[] deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
    PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_SIZE);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
    } finally {
      spec.clearPassword();
    }
  }

  public static final class ImportedBackup {
    public final List<ImportedProxy> proxies;
    public final ImportedSshTunnel sshTunnel;
    ImportedBackup(List<ImportedProxy> proxies, ImportedSshTunnel sshTunnel) {
      this.proxies = proxies;
      this.sshTunnel = sshTunnel;
    }
  }

  public static final class ImportedSshTunnel {
    public final String host, username, password, knownHosts;
    public final int sshPort, remotePort;
    public final boolean enabled;
    ImportedSshTunnel(String host, int sshPort, String username, String password,
                      int remotePort, String knownHosts, boolean enabled) {
      this.host = host;
      this.sshPort = sshPort;
      this.username = username;
      this.password = password;
      this.remotePort = remotePort;
      this.knownHosts = knownHosts;
      this.enabled = enabled;
    }
  }

  public static final class ImportedProxy {
    public final TdApi.InternalLinkTypeProxy proxy;
    public final String description;
    ImportedProxy(TdApi.InternalLinkTypeProxy proxy, String description) {
      this.proxy = proxy;
      this.description = description;
    }
  }
}
