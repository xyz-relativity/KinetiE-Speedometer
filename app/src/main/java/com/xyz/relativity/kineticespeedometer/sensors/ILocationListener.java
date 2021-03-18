package com.xyz.relativity.kineticespeedometer.sensors;

import android.location.Location;

/**
 * Created by xyz on 1/19/18.
 */

public interface ILocationListener {
	void updatePosition(Location location);
}
