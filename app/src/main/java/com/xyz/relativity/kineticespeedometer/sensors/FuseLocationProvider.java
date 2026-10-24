package com.xyz.relativity.kineticespeedometer.sensors;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.List;

/**
 * Subscribes to a single location provider, GPS when available, and forwards every new fix.
 *
 * <p>One provider only, on purpose: the GPS provider is the one that reports Doppler speed, and
 * mixing providers produced duplicate and stale callbacks. The speed estimator weighs each fix by
 * its own reported accuracy, so no quality gating happens here. Stale last-known locations are
 * not replayed either; a minutes-old speed is useless to a speedometer.
 */
public class FuseLocationProvider implements LocationListener {
	private static final float MINIMUM_DISTANCE_METERS = 0f;

	public interface LocationEvent {
		void onLocationChanged(Location location);
	}

	private final LocationEvent eventListener;
	private final long minTimeMs;
	private final LocationManager locationManager;
	private final AppCompatActivity parent;
	private long lastFixElapsedNanos = Long.MIN_VALUE;
	private String activeProvider;

	public FuseLocationProvider(AppCompatActivity parent, int minTimeMs, LocationEvent eventListener) {
		this.parent = parent;
		this.eventListener = eventListener;
		this.minTimeMs = minTimeMs;
		this.locationManager = (LocationManager) parent.getSystemService(Context.LOCATION_SERVICE);
	}

	public void onResume() {
		if (!hasLocationPermission()) {
			return;
		}
		String provider = pickProvider();
		if (provider == null) {
			return;
		}
		activeProvider = provider;
		locationManager.requestLocationUpdates(provider, minTimeMs, MINIMUM_DISTANCE_METERS,
				this, Looper.getMainLooper());
	}

	public void onPause() {
		locationManager.removeUpdates(this);
		activeProvider = null;
	}

	/** Provider currently subscribed to, or null while paused or without permission. */
	public String getActiveProvider() {
		return activeProvider;
	}

	private boolean hasLocationPermission() {
		return ActivityCompat.checkSelfPermission(parent, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
				|| ActivityCompat.checkSelfPermission(parent, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
	}

	private String pickProvider() {
		List<String> providers = locationManager.getAllProviders();
		if (providers.contains(LocationManager.GPS_PROVIDER)) {
			return LocationManager.GPS_PROVIDER;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && providers.contains(LocationManager.FUSED_PROVIDER)) {
			return LocationManager.FUSED_PROVIDER;
		}
		if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
			return LocationManager.NETWORK_PROVIDER;
		}
		return null;
	}

	@Override
	public void onLocationChanged(@NonNull Location location) {
		long fixNanos = location.getElapsedRealtimeNanos();
		if (fixNanos <= lastFixElapsedNanos) {
			return; // duplicate or out-of-order delivery
		}
		lastFixElapsedNanos = fixNanos;
		eventListener.onLocationChanged(location);
	}

	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {
	}

	@Override
	public void onProviderEnabled(@NonNull String provider) {
	}

	@Override
	public void onProviderDisabled(@NonNull String provider) {
	}
}
