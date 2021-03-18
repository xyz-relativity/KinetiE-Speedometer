package com.xyz.relativity.kineticespeedometer.sensors;

import android.location.Location;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by xyz on 12/6/17.
 */

public class DeviceLocationManager implements FuseLocationProvider.LocationEvent {
	private AppCompatActivity parent;
	private final List<ILocationListener> eventsHandler = new ArrayList<>();
	private final FuseLocationProvider fusedLocationManager;

	public DeviceLocationManager(AppCompatActivity parent, int intervalMs, ILocationListener pEventsHandler) {
		this.parent = parent;
		addListener(pEventsHandler);

		fusedLocationManager = new FuseLocationProvider(parent, intervalMs, this);
	}

	public void addListener(ILocationListener... pEventsHandler) {
		for (ILocationListener i : pEventsHandler) {
			if (!eventsHandler.contains(i)) {
				eventsHandler.add(i);
			}
		}

	}

	public void onResume() {
		fusedLocationManager.onResume();
	}

	public void onPause() {
		fusedLocationManager.onPause();
	}

	@Override
	public void onLocationChanged(Location location) {
		for (ILocationListener client : eventsHandler) {
			client.updatePosition(location);
		}
	}
}
