package com.xyz.relativity.kineticespeedometer;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

public class KinetieApplication extends Application {
	@Override
	public void onCreate() {
		super.onCreate();
		AppCompatDelegate.setDefaultNightMode(new AppSettings(this).nightMode());
	}
}
