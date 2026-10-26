package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.telegram.Tdlib;
import android.location.Location;

/**
 * Map-free controller used by builds that do not include a map provider.
 * Location sharing and coordinate messages remain available through the location picker.
 */
final class MapNoMapController extends MapController<View, Object> {
  MapNoMapController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  protected View createMapView (Context context, int marginBottom) {
    TextView view = new TextView(context);
    view.setGravity(Gravity.CENTER);
    view.setText(R.string.MapUnavailable);
    view.setTextColor(Theme.getColor(ColorId.text));
    view.setPadding(Screen.dp(24f), Screen.dp(24f), Screen.dp(24f), Screen.dp(24f));
    return view;
  }

  @Override protected boolean needBackgroundMapInitialization (@NonNull View mapView) { return false; }
  @Override protected void initializeMap (@NonNull View mapView, boolean inBackground) { }
  @Override protected void resumeMap (@NonNull View mapView) { }
  @Override protected void pauseMap (@NonNull View mapView) { }
  @Override protected void destroyMap (@NonNull View mapView) { }
  @Override protected boolean displayMyLocation (@NonNull View mapView) { return false; }
  @Override protected boolean onPositionRequested (@NonNull View mapView, @Nullable LocationPoint<Object> point, boolean animated, boolean needBearing, boolean onlyFocus) { return false; }
  @Override protected void onApplyMapType (int oldType, int newType) { }
  @Override protected boolean onBuildDirectionTo (@NonNull View mapView, double latitude, double longitude) { return false; }
  @Override protected boolean onStartPeriodicBearingUpdates (@NonNull View mapView) { return false; }
  @Override protected void onFinishPeriodicBearingUpdates (@NonNull View mapView) { }
  @Override protected boolean wouldRememberMapType (int newMapType) { return false; }
  @Override protected int[] getAvailableMapTypes () { return new int[0]; }
}
