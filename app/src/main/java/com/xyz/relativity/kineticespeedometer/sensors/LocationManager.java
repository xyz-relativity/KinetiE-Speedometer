package com.xyz.relativity.kineticespeedometer.sensors;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by xyz on 12/6/17.
 */

public class LocationManager implements LocationListener, OnSuccessListener<Location> {

    private FusedLocationProviderClient mFusedLocationClient;
    private LocationCallback mLocationCallback;
    private LocationRequest mLocationRequest = new LocationRequest();
    private AppCompatActivity parent;
    private List<ILocationListener> eventsHandler = new ArrayList<>();

    public LocationManager(AppCompatActivity parent, int frequency) {
        this.parent = parent;

        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    onLocationChanged(location);
                }
            }
        };
        mLocationRequest.setInterval(frequency);
        mLocationRequest.setFastestInterval(frequency);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(parent);
        if (ActivityCompat.checkSelfPermission(parent, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(parent, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        mFusedLocationClient.getLastLocation().addOnSuccessListener(parent, this);
    }

    public void addListener(ILocationListener... pEventsHandler) {
        for (ILocationListener i : pEventsHandler) {
            if (!eventsHandler.contains(i)) {
                eventsHandler.add(i);
            }
        }

    }

    public void onResume() {
        if (ActivityCompat.checkSelfPermission(parent, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(parent, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        mFusedLocationClient.requestLocationUpdates(mLocationRequest,
                mLocationCallback,
                null /* Looper */);
    }

    public void onPause() {
        mFusedLocationClient.removeLocationUpdates(mLocationCallback);
    }

    @Override
    public void onLocationChanged(Location location) {
        for (ILocationListener client : eventsHandler) {
            client.updatePosition(location);
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onProviderDisabled(String provider) {

    }

    @Override
    public void onSuccess(Location location) {
        // Got last known location. In some rare situations this can be null.
        if (location != null) {
            onLocationChanged(location);
        }
    }
}
