package org.thunderdog.challegram.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class SshTunnelService extends Service {
  private static final String STOP = "org.thunderdog.challegram.STOP_SSH_TUNNEL";
  private static final String CHANNEL = "ssh_tunnel";
  private static final int NOTIFICATION_ID = 43108, LOCAL_PORT = 1080;
  private volatile boolean running;
  private Thread worker;
  private volatile Session session;

  public static void start (Context c) {
    SshTunnelConfig.setEnabled(c, true);
    Intent i = new Intent(c, SshTunnelService.class);
    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
  }
  public static void startIfEnabled (Context c) { if (SshTunnelConfig.isEnabled(c)) start(c); }
  public static void stop (Context c) {
    SshTunnelConfig.setEnabled(c, false);
    Intent i = new Intent(c, SshTunnelService.class).setAction(STOP);
    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
  }
  @Nullable @Override public IBinder onBind (Intent intent) { return null; }

  @Override public void onCreate () {
    super.onCreate();
    UI.initApp(getApplicationContext());
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      if (nm.getNotificationChannel(CHANNEL) == null)
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "SSH tunnel", NotificationManager.IMPORTANCE_LOW));
    }
  }

  @Override public int onStartCommand (Intent intent, int flags, int startId) {
    startForeground(NOTIFICATION_ID, notification());
    if (intent != null && STOP.equals(intent.getAction())) {
      running = false;
      if (worker != null) worker.interrupt();
      disconnect();
      restoreProxy();
      stopForeground(true);
      stopSelf(startId);
      return START_NOT_STICKY;
    }
    if (worker == null || !worker.isAlive()) {
      rememberProxy();
      running = true;
      worker = new Thread(this::tunnelLoop, "SshTunnelWorker");
      worker.start();
    }
    return START_STICKY;
  }

  private Notification notification () {
    Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
    return b.setSmallIcon(R.drawable.baseline_notifications_active_24)
      .setContentTitle("SSH tunnel").setContentText("Forwarding the SOCKS5 proxy").setOngoing(true).build();
  }

  private void rememberProxy () {
    android.content.SharedPreferences p = getSharedPreferences("ssh_tunnel_config", MODE_PRIVATE);
    if (p.getBoolean("previous_saved", false)) return;
    Settings s = Settings.instance();
    p.edit().putBoolean("previous_enabled", s.getEffectiveProxyId() != Settings.PROXY_ID_NONE)
      .putInt("previous_proxy_id", s.getAvailableProxyId()).putBoolean("previous_saved", true).apply();
  }

  private void tunnelLoop () {
    while (running) {
      try {
        SshTunnelConfig c = SshTunnelConfig.load(this);
        if (c.host.isEmpty() || c.username.isEmpty() || c.password.isEmpty() || c.knownHosts.isEmpty())
          throw new IllegalStateException("SSH tunnel settings are incomplete");
        File kh = new File(getFilesDir(), "ssh_tunnel_known_hosts");
        FileOutputStream out = new FileOutputStream(kh, false);
        try { out.write(c.knownHosts.trim().getBytes(StandardCharsets.UTF_8)); out.write('\n'); } finally { out.close(); }
        JSch jsch = new JSch();
        jsch.setKnownHosts(kh.getAbsolutePath());
        HostKey[] loaded = jsch.getHostKeyRepository().getHostKey(c.host, null);
        android.util.Log.i("SshTunnelService", "Known-host check: host=" + c.host + ", port=" + c.sshPort
          + ", fileExists=" + kh.isFile() + ", fileLength=" + kh.length()
          + ", loadedEntries=" + (loaded == null ? 0 : loaded.length));
        if (loaded != null) {
          for (HostKey key : loaded) {
            android.util.Log.i("SshTunnelService", "Loaded known host: host=" + key.getHost()
              + ", type=" + key.getType());
          }
        }
        Session s = jsch.getSession(c.username, c.host, c.sshPort);
        s.setPassword(c.password);
        Properties options = new Properties();
        options.setProperty("StrictHostKeyChecking", "yes");
        options.setProperty("PreferredAuthentications", "password");
        s.setConfig(options);
        s.setServerAliveInterval(10000);
        s.setServerAliveCountMax(3);
        s.connect(15000);
        s.setPortForwardingL(LOCAL_PORT, "127.0.0.1", c.remotePort);
        session = s;
        applyProxy();
        while (running && s.isConnected()) Thread.sleep(1000);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      } catch (Throwable e) {
        android.util.Log.e("SshTunnelService", "SSH connection failed", e);
      } finally { disconnect(); }
      if (running) try { Thread.sleep(5000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
  }

  private void applyProxy () {
    Settings settings = Settings.instance();
    android.content.SharedPreferences p = getSharedPreferences("ssh_tunnel_config", MODE_PRIVATE);
    Settings.Proxy old = settings.getProxyConfig(p.getInt("previous_proxy_id", Settings.PROXY_ID_NONE));
    String user = "", pass = "";
    if (old != null && old.proxy != null && old.proxy.type.getConstructor() == TdApi.ProxyTypeSocks5.CONSTRUCTOR) {
      TdApi.ProxyTypeSocks5 socks = (TdApi.ProxyTypeSocks5) old.proxy.type;
      user = socks.username; pass = socks.password;
    }
    TdApi.InternalLinkTypeProxy local = new TdApi.InternalLinkTypeProxy("127.0.0.1", LOCAL_PORT, new TdApi.ProxyTypeSocks5(user, pass));
    int id = p.getInt("tunnel_proxy_id", Settings.PROXY_ID_NONE);
    if (id > Settings.PROXY_ID_NONE) id = settings.addOrUpdateProxy(local, "SSH tunnel", true, id);
    else id = settings.addOrUpdateProxy(local, "SSH tunnel", true);
    p.edit().putInt("tunnel_proxy_id", id).apply();
  }

  private void restoreProxy () {
    Settings s = Settings.instance();
    android.content.SharedPreferences p = getSharedPreferences("ssh_tunnel_config", MODE_PRIVATE);
    int oldId = p.getInt("previous_proxy_id", Settings.PROXY_ID_NONE);
    int tunnelId = p.getInt("tunnel_proxy_id", Settings.PROXY_ID_NONE);
    boolean wasEnabled = p.getBoolean("previous_enabled", false);
    Settings.Proxy old = s.getProxyConfig(oldId);
    if (old != null && old.proxy != null) {
      s.addOrUpdateProxy(old.proxy, old.description, true, oldId);
      if (!wasEnabled) s.disableProxy();
    } else s.disableProxy();
    if (tunnelId > Settings.PROXY_ID_NONE && tunnelId != oldId) s.removeProxy(tunnelId);
    p.edit().remove("previous_saved").remove("previous_enabled").remove("previous_proxy_id").remove("tunnel_proxy_id").apply();
  }

  private void disconnect () {
    Session s = session; session = null;
    if (s != null) try { s.disconnect(); } catch (Throwable ignored) { }
  }
  @Override public void onDestroy () { running = false; disconnect(); super.onDestroy(); }
}
