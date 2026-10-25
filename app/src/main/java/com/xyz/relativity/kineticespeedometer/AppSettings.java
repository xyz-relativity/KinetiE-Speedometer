package com.xyz.relativity.kineticespeedometer;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

/** Typed access to the user settings edited on the settings screen. */
public final class AppSettings {
	public static final String KEY_SPEED_UNIT = "speed_unit";
	public static final String KEY_GAUGE_MAX = "gauge_max";
	public static final String KEY_VEHICLE_MASS = "vehicle_mass";
	public static final String KEY_THEME = "theme";
	public static final String KEY_RECORD_SENSORS = "record_sensors";

	public static final String THEME_NIGHT = "night";
	public static final String THEME_DAY = "day";
	public static final String THEME_SYSTEM = "system";

	/** Default dial maximum, as a km/h value; other units get its conversion. */
	public static final float DEFAULT_GAUGE_MAX_KPH = 200f;
	public static final float MIN_GAUGE_MAX_MPS = 5f;
	public static final float MAX_GAUGE_MAX_MPS = 150f;

	public enum SpeedUnit {
		KPH("kph", 3.6f, 1000f, R.string.unit_kph, R.string.unit_km, 20, 10),
		MPH("mph", 2.2369363f, 1609.344f, R.string.unit_mph, R.string.unit_mi, 20, 10),
		MPS("mps", 1f, 1000f, R.string.unit_mps, R.string.unit_km, 10, 5);

		public final String key;
		/** Display units per metre per second. */
		public final float perMps;
		/** Metres in one distance unit shown with this speed unit. */
		public final float metresPerDistanceUnit;
		public final int speedLabel;
		public final int distanceLabel;
		/** Spacing of the labelled dial ticks, in display units. */
		public final int majorLabelStep;
		/** Spacing of the unlabelled dial ticks, in display units. */
		public final int minorTickStep;

		SpeedUnit(String key, float perMps, float metresPerDistanceUnit, int speedLabel, int distanceLabel,
				int majorLabelStep, int minorTickStep) {
			this.key = key;
			this.perMps = perMps;
			this.metresPerDistanceUnit = metresPerDistanceUnit;
			this.speedLabel = speedLabel;
			this.distanceLabel = distanceLabel;
			this.majorLabelStep = majorLabelStep;
			this.minorTickStep = minorTickStep;
		}

		public float fromMps(float metresPerSecond) {
			return metresPerSecond * perMps;
		}

		public float toMps(float displayValue) {
			return displayValue / perMps;
		}

		public static SpeedUnit fromKey(String key) {
			for (SpeedUnit unit : values()) {
				if (unit.key.equals(key)) {
					return unit;
				}
			}
			return KPH;
		}
	}

	private final SharedPreferences preferences;

	public AppSettings(Context context) {
		preferences = PreferenceManager.getDefaultSharedPreferences(context);
	}

	public SpeedUnit speedUnit() {
		return SpeedUnit.fromKey(preferences.getString(KEY_SPEED_UNIT, SpeedUnit.KPH.key));
	}

	/** Dial maximum in m/s, clamped to a sane range. */
	public float gaugeMaxMps() {
		SpeedUnit unit = speedUnit();
		float fallback = unit.fromMps(SpeedUnit.KPH.toMps(DEFAULT_GAUGE_MAX_KPH));
		float display = parseFloat(preferences.getString(KEY_GAUGE_MAX, null), fallback);
		return Math.max(MIN_GAUGE_MAX_MPS, Math.min(MAX_GAUGE_MAX_MPS, unit.toMps(display)));
	}

	/** Vehicle mass in kg, or 0 when energy is shown per kilogram. */
	public float vehicleMassKg() {
		return Math.max(0f, parseFloat(preferences.getString(KEY_VEHICLE_MASS, ""), 0f));
	}

	public String theme() {
		return preferences.getString(KEY_THEME, THEME_NIGHT);
	}

	public int nightMode() {
		return nightModeFor(theme());
	}

	public static int nightModeFor(String theme) {
		if (THEME_DAY.equals(theme)) {
			return AppCompatDelegate.MODE_NIGHT_NO;
		}
		if (THEME_SYSTEM.equals(theme)) {
			return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
		}
		return AppCompatDelegate.MODE_NIGHT_YES;
	}

	public boolean recordSensors() {
		return preferences.getBoolean(KEY_RECORD_SENSORS, false);
	}

	public void setRecordSensors(boolean enabled) {
		preferences.edit().putBoolean(KEY_RECORD_SENSORS, enabled).apply();
	}

	static float parseFloat(String text, float fallback) {
		if (text == null) {
			return fallback;
		}
		try {
			return Float.parseFloat(text.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
