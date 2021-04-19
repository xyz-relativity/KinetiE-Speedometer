package com.xyz.relativity.kineticespeedometer.sensors;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.concurrent.TimeUnit;

public class FuseLocationProvider implements LocationListener {
	private static final float MINIMUM_DISTANCE_METERS = 1f;

	private final LocationEvent eventListener;
	private final int intervalMs;

	public interface LocationEvent {
		void onLocationChanged(Location location);
	}

	private final LocationManager locationManager;
	private final AppCompatActivity parent;
	private Location lastLocation;

	public FuseLocationProvider(AppCompatActivity parent, int intervalMs, LocationEvent eventListener) {
		this.parent = parent;
		this.eventListener = eventListener;
		this.intervalMs = intervalMs;

		this.locationManager = (LocationManager) parent
				.getSystemService(Context.LOCATION_SERVICE);
	}

	private void initLocation() {
		if (ActivityCompat.checkSelfPermission(parent, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
				&& ActivityCompat.checkSelfPermission(parent, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			return;
		}
		lastLocation = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);

		if (lastLocation != null) {
			updateListeners();
		} else {
			lastLocation = new Location(LocationManager.PASSIVE_PROVIDER);
			lastLocation.setAccuracy(999);
		}
	}

	public void onResume() {
		if (ActivityCompat.checkSelfPermission(parent, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
				&& ActivityCompat.checkSelfPermission(parent, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			return;
		}

		initLocation();

		for (String providerStr: locationManager.getAllProviders()) {
			if (providerStr.equalsIgnoreCase(LocationManager.PASSIVE_PROVIDER)) {
				continue;
			}

			locationManager.requestLocationUpdates(providerStr, intervalMs,
					MINIMUM_DISTANCE_METERS, FuseLocationProvider.this);
		}
	}

	public void onPause() {
		locationManager.removeUpdates(this);
	}

	private void updateListeners() {
		eventListener.onLocationChanged(lastLocation);
	}

	@Override
	public void onLocationChanged(@NonNull Location location) {
		long compareTime = SystemClock.elapsedRealtimeNanos();
		float oldLocationAccuracy = Math.max(0, lastLocation.getAccuracy() + TimeUnit.NANOSECONDS.toSeconds(compareTime - lastLocation.getElapsedRealtimeNanos()));
		float newLocationAccuracy = Math.max(0, location.getAccuracy() + TimeUnit.NANOSECONDS.toSeconds(compareTime - location.getElapsedRealtimeNanos()));

		if (newLocationAccuracy <= oldLocationAccuracy) {
			lastLocation = new Location(location);
		}
		updateListeners();
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
