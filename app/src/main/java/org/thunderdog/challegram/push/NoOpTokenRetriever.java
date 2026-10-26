package org.thunderdog.challegram.push;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.util.TokenRetriever;

/**
 * Push registration fallback for builds without Google/Firebase or vendor push services.
 * Telegram remains usable; push notifications are unavailable until a push provider is configured.
 */
public final class NoOpTokenRetriever extends TokenRetriever {
  @Override
  protected boolean performInitialization (Context context) {
    return true;
  }

  @Override
  public @NonNull String getName () {
    return "none";
  }

  @Override
  public @Nullable String getConfiguration () {
    return null;
  }

  @Override
  protected void fetchDeviceToken (int retryCount, RegisterCallback callback) {
    callback.onError("PUSH_NOT_AVAILABLE", null);
  }
}
