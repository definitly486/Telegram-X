package org.thunderdog.challegram.component.attach;

import android.content.Context;
import android.location.Location;
import android.view.View;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.helper.LocationHelper;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.util.ActivityPermissionResult;

import me.vkryl.android.widget.FrameLayoutFix;

/**
 * Map-free location picker support. Coordinates can still be selected and sent;
 * the embedded Google map is intentionally omitted in builds without Google services.
 */
public final class MediaLocationMapView extends FrameLayoutFix implements View.OnClickListener, ActivityPermissionResult {
  public interface Callback {
    void onLocationUpdate (Location location, boolean custom, boolean gpsLocated, boolean preventRequest, boolean isSmallZoom);
    void onForcedLocationReset ();
  }

  private Callback callback;
  private Location currentLocation;
  private boolean destroyed;

  public static int getMapHeight (boolean big) {
    return 0;
  }

  public MediaLocationMapView (Context context) {
    super(context);
  }

  public void init (ViewController<?> themeProvider, MediaLocationPointView pointView, boolean big) {
    currentLocation = MediaLocationFinder.instance().getLastKnownLocation();
  }

  public void setCallback (Callback callback) {
    this.callback = callback;
    dispatchCurrentLocation(false);
  }

  public void setMarkerPosition (double latitude, double longitude) {
    currentLocation = new Location("manual");
    currentLocation.setLatitude(latitude);
    currentLocation.setLongitude(longitude);
    dispatchCurrentLocation(true);
  }

  public void onPauseMap () { }

  public void onResumeMap () {
    if (!destroyed) {
      currentLocation = MediaLocationFinder.instance().getLastKnownLocation();
      dispatchCurrentLocation(false);
    }
  }

  public void onDestroyMap () {
    destroyed = true;
  }

  public void onResolutionComplete (boolean isOk) {
    checkLocationSettings(isOk, false);
  }

  @Override
  public void onPermissionResult (int code, String[] permissions, int[] grantResults, int grantCount) {
    if (grantCount == permissions.length) {
      onLocationPermissionOk();
    }
  }

  public void onLocationPermissionOk () {
    checkLocationSettings(true, false);
  }

  public void checkLocationSettings (boolean requestedByUser, boolean disablePrompts) {
    if (destroyed) return;
    Location cached = MediaLocationFinder.instance().getLastKnownLocation();
    if (cached != null) {
      currentLocation = cached;
      dispatchCurrentLocation(false);
    }
    if (!requestedByUser || !(getContext() instanceof BaseActivity)) return;
    LocationHelper.requestLocation((BaseActivity) getContext(), 10000L, false, false, (errorCode, location) -> {
      if (destroyed) return;
      if (location != null) currentLocation = location;
      else if (currentLocation == null) currentLocation = MediaLocationFinder.instance().getLastKnownLocation();
      dispatchCurrentLocation(false);
    });
  }

  @Nullable
  public Location getCurrentLocation () {
    return currentLocation;
  }

  @Override
  public void onClick (View view) {
    checkLocationSettings(true, false);
  }

  private void dispatchCurrentLocation (boolean custom) {
    if (callback != null) {
      callback.onLocationUpdate(currentLocation, custom, currentLocation != null, custom, true);
    }
  }
}
