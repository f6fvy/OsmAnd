package net.osmand.plus.views.mapwidgets.widgets;

import android.graphics.drawable.Drawable;

import net.osmand.Location;
import net.osmand.data.LatLon;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.mapmarkers.MapMarker;
import net.osmand.plus.mapmarkers.MapMarkersHelper;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.settings.backend.preferences.OsmandPreference;
import net.osmand.plus.settings.enums.MetricsConstants;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.plus.utils.OsmAndFormatter.FormattedValue;
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings;
import net.osmand.plus.views.mapwidgets.AverageSpeedComputer;
import net.osmand.plus.views.mapwidgets.MarkersWidgetsHelper;
import net.osmand.plus.views.mapwidgets.MarkersWidgetsHelper.CustomLatLonListener;
import net.osmand.plus.views.mapwidgets.widgetstates.MapMarkerSideWidgetState;
import net.osmand.plus.views.mapwidgets.widgetstates.MapMarkerSideWidgetState.SideMarkerMode;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static net.osmand.plus.views.mapwidgets.WidgetType.SIDE_MARKER_1;
import static net.osmand.plus.views.mapwidgets.WidgetType.SIDE_MARKER_2;

public class MapMarkerSideWidget extends TextInfoWidget implements CustomLatLonListener {

	private static final String DASH = "—";
	private static final int UPDATE_INTERVAL_MILLIS = 1000;

	private final MapMarkersHelper mapMarkersHelper;
	private final MapMarkerSideWidgetState widgetState;
	private final OsmandPreference<SideMarkerMode> markerModePref;

	private SideMarkerMode cachedMode;
	private int cachedMeters;
	private long lastUpdatedTime;
	private int cachedMarkerColorIndex = -1;
	private boolean cachedNightMode;

	private LatLon customLatLon;

	public MapMarkerSideWidget(@NonNull MapActivity mapActivity, @NonNull MapMarkerSideWidgetState widgetState) {
		super(mapActivity, widgetState.isFirstMarker() ? SIDE_MARKER_1 : SIDE_MARKER_2);
		this.widgetState = widgetState;
		this.mapMarkersHelper = app.getMapMarkersHelper();
		this.markerModePref = widgetState.getMapMarkerModePref();

		cachedNightMode = isNightMode();

		setText(null, null);
		setOnClickListener(v -> {
			widgetState.changeToNextState();
			updateInfo(null);
		});
		view.setOnLongClickListener(v -> {
			MarkersWidgetsHelper.showMarkerOnMap(mapActivity, widgetState.isFirstMarker() ? 0 : 1);
			return true;
		});
	}

	@NonNull
	@Override
	public MapMarkerSideWidgetState getWidgetState() {
		return widgetState;
	}

	@Override
	public void setCustomLatLon(@Nullable LatLon customLatLon) {
		this.customLatLon = customLatLon;
	}

	@Override
	public void updateInfo(@Nullable DrawSettings drawSettings) {
		RoutingHelper routingHelper = app.getRoutingHelper();
		MapMarker marker = getMarker();

		boolean hideWidget = marker == null
				|| routingHelper.isRoutePlanningMode()
				|| routingHelper.isFollowingMode();
		if (hideWidget) {
			cachedMeters = 0;
			lastUpdatedTime = 0;
			setText(null, null);
			return;
		}

		SideMarkerMode newMode = markerModePref.get();
		boolean modeChanged = cachedMode != newMode;
		if (modeChanged) {
			cachedMode = newMode;
		}

		updateTextIfNeeded(newMode, modeChanged);
		updateIconIfNeeded(marker, newMode, modeChanged);
	}

	private void updateTextIfNeeded(@NonNull SideMarkerMode mode, boolean modeChanged) {
		int distance = getDistance();
		long time = System.currentTimeMillis();

		boolean distanceChanged = cachedMeters != distance;
		boolean timePassed = time - lastUpdatedTime > UPDATE_INTERVAL_MILLIS;
		boolean updateDistance = mode == SideMarkerMode.DISTANCE && distanceChanged;
		boolean updateArrivalTime = mode == SideMarkerMode.ESTIMATED_ARRIVAL_TIME && (distanceChanged || timePassed);

		if (isUpdateNeeded() || modeChanged || updateDistance || updateArrivalTime) {
			if (mode == SideMarkerMode.DISTANCE) {
				updateDistance(distance);
			} else if (mode == SideMarkerMode.ESTIMATED_ARRIVAL_TIME) {
				updateArrivalTime(distance, time);
			}
		}
	}

	private void updateDistance(int distance) {
		cachedMeters = distance;
		MetricsConstants metricsConstants = settings.METRIC_SYSTEM.get();
		FormattedValue formattedDistance = OsmAndFormatter.getFormattedDistanceValue(distance,
				app, false, metricsConstants);
		setText(formattedDistance.value, formattedDistance.unit);
	}

	private void updateArrivalTime(int distance, long currentTime) {
		cachedMeters = distance;
		lastUpdatedTime = currentTime;

		AverageSpeedComputer averageSpeedComputer = app.getAverageSpeedComputer();
		long interval = widgetState.getAverageSpeedIntervalPref().get();
		float averageSpeed = averageSpeedComputer.getAverageSpeed(interval, false);

		if (Float.isNaN(averageSpeed) || averageSpeed == 0) {
			setText(DASH, null);
			return;
		}

		int estimatedLeftSeconds = (int) (distance / averageSpeed);
		long estimatedArrivalTime = currentTime + estimatedLeftSeconds * 1000L;
		setTimeText(estimatedArrivalTime);
	}

	private void updateIconIfNeeded(@NonNull MapMarker marker, @NonNull SideMarkerMode mode, boolean modeChanged) {
		int colorIndex = marker.colorIndex;
		boolean colorChanged = colorIndex != -1
				&& (colorIndex != cachedMarkerColorIndex || cachedNightMode != isNightMode());
		if (colorChanged || modeChanged) {
			cachedMarkerColorIndex = colorIndex;
			cachedNightMode = isNightMode();

			int backgroundIconId = widgetState.getSettingsIconId(cachedNightMode);
			int foregroundColorId = MapMarker.getColorId(colorIndex);
			Drawable drawable = iconsCache.getLayeredIcon(backgroundIconId,
					mode.foregroundIconId, 0, foregroundColorId);
			setImageDrawable(drawable);
		}
	}

	public int getDistance() {
		int distance = 0;
		LatLon pointToNavigate = getPointToNavigate();
		if (pointToNavigate != null) {
			LatLon latLon = customLatLon != null ? customLatLon : MarkersWidgetsHelper.getDefaultLatLon(mapActivity);
			float[] calc = new float[1];
			Location.distanceBetween(latLon.getLatitude(), latLon.getLongitude(), pointToNavigate.getLatitude(), pointToNavigate.getLongitude(), calc);
			distance = (int) calc[0];
		}
		return distance;
	}

	@Nullable
	private LatLon getPointToNavigate() {
		MapMarker marker = getMarker();
		return marker != null ? marker.point : null;
	}

	@Nullable
	private MapMarker getMarker() {
		List<MapMarker> markers = mapMarkersHelper.getMapMarkers();
		if (markers.size() > 0) {
			if (widgetState.isFirstMarker()) {
				return markers.get(0);
			} else if (markers.size() > 1) {
				return markers.get(1);
			}
		}
		return null;
	}

	@Override
	public boolean isMetricSystemDepended() {
		return true;
	}
}