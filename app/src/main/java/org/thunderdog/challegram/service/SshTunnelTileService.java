package org.thunderdog.challegram.service;

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile for starting and stopping the SSH SOCKS5 tunnel. */
public final class SshTunnelTileService extends TileService {
  @Override
  public void onStartListening () {
    super.onStartListening();
    updateTileState();
  }

  @Override
  public void onClick () {
    super.onClick();
    if (SshTunnelConfig.isEnabled(this)) {
      SshTunnelService.stop(this);
    } else {
      SshTunnelService.start(this);
    }
    updateTileState();
  }

  private void updateTileState () {
    Tile tile = getQsTile();
    if (tile == null) return;

    boolean enabled = SshTunnelConfig.isEnabled(this);
    tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
    tile.setLabel("SSH tunnel");
    tile.setContentDescription(enabled ? "Stop SSH tunnel" : "Start SSH tunnel");
    if (Build.VERSION.SDK_INT >= 29) {
      tile.setSubtitle(enabled ? "On" : "Off");
    }
    tile.updateTile();
  }
}
