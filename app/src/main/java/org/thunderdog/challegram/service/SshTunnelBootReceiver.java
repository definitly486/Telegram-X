package org.thunderdog.challegram.service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public class SshTunnelBootReceiver extends BroadcastReceiver {
  @Override public void onReceive (Context context, Intent intent) {
    if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) SshTunnelService.startIfEnabled(context);
  }
}
