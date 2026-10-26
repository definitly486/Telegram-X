package org.thunderdog.challegram.location;

import android.content.pm.PackageManager;
import android.location.Location;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.helper.LocationHelper;
import org.thunderdog.challegram.unsorted.LocationRetriever;

import me.vkryl.core.lambda.RunnableBool;

/** Native Android location retriever that does not use Google Play Services. */
public final class AndroidLocationRetriever extends LocationRetriever {
  public AndroidLocationRetriever (BaseActivity activity) {
    super(activity);
  }

  @Override
  public void performDestroy () { }

  @Override
  public void checkPermissions (RunnableBool callback) {
    if (activity.checkLocationPermissions(false) == PackageManager.PERMISSION_GRANTED) {
      callback.runWithBool(true);
      return;
    }
    activity.requestLocationPermission(false, false, () -> callback.runWithBool(false),
      (code, permissions, grantResults, grantCount) -> callback.runWithBool(grantCount == permissions.length));
  }

  @Override
  protected void retrieveLocation () {
    LocationHelper.requestLocation(activity, 10000L, true, false, (errorCode, location) -> {
      if (location != null) {
        onLocationRetrieved(location);
      } else {
        onLocationFetchFailed();
      }
    });
  }
}
