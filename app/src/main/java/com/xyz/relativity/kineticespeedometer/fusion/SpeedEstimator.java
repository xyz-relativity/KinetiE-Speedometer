package com.xyz.relativity.kineticespeedometer.fusion;

/**
 * Fuses linear acceleration, gravity and GPS speed into a smooth forward-speed estimate.
 *
 * <p>Per sensor sample: remove the gravity component from the device-frame linear acceleration,
 * project the remainder onto the learned forward axis, run the Kalman prediction, and apply a
 * zero-velocity update when the stationary detector fires. Each GPS fix trains the forward axis
 * and corrects the filter with latency compensation. No magnetometer or heading is involved, so
 * in-car magnetic disturbances cannot leak cornering into the speed.
 *
 * <p>Until the forward axis is learned the filter is driven by the acceleration implied by the
 * last GPS speed changes, which keeps the needle moving between fixes without inertial drift.
 *
 * <p>All methods must be called from one thread. Timestamps are elapsed-realtime nanoseconds, the
 * clock shared by SensorEvent.timestamp and Location.getElapsedRealtimeNanos().
 */
public final class SpeedEstimator {
	public enum Mode { NO_FIX, GPS, DEAD_RECKONING }

	public static final float DEFAULT_GPS_SPEED_SIGMA_MPS = 0.5f;
	public static final float MIN_GPS_SPEED_SIGMA_MPS = 0.15f;
	public static final float MAX_GPS_SPEED_SIGMA_MPS = 5f;
	/** Without a fix for this long the estimate runs on dead reckoning alone. */
	public static final long GPS_STALE_NS = 3_000_000_000L;
	/**
	 * Extra age assumed for every fix beyond its own timestamp. Devices that stamp fixes with the
	 * delivery time rather than the measurement time benefit from 300 to 800 ms here.
	 */
	public static final long GPS_EXTRA_LATENCY_NS = 0L;

	static final float STATIONARY_GPS_SPEED_MPS = 0.8f;
	static final float STATIONARY_STD_WITH_GPS_MPS2 = 0.15f;
	static final float STATIONARY_STD_WITHOUT_GPS_MPS2 = 0.07f;
	static final float STATIONARY_MEAN_MAX_MPS2 = 0.3f;
	static final float MAX_SAMPLE_DT_SEC = 0.5f;
	static final float MIN_GPS_ACCEL_WINDOW_SEC = 0.8f;
	static final float MAX_GPS_ACCEL_WINDOW_SEC = 3f;
	static final float MAX_GPS_ACCEL_MPS2 = 5f;
	static final long GPS_ACCEL_HOLD_NS = 2_000_000_000L;
	private static final float MIN_GRAVITY_MPS2 = 1f;
	private static final float ACCEL_DISPLAY_SMOOTHING = 0.1f;
	private static final float NANOS_PER_SECOND = 1e9f;

	private final SpeedKalmanFilter filter = new SpeedKalmanFilter();
	private final ForwardAxisEstimator axis = new ForwardAxisEstimator();
	private final StationaryDetector stationaryDetector = new StationaryDetector();

	private final float[] gravityUnit = new float[3];
	private final float[] horizontal = new float[3];

	private long lastSampleNs;
	private boolean hasSample;
	private long lastGpsNs;
	private float lastGpsSpeed;
	private boolean hasGps;
	private long accelWindowStartNs;
	private float accelWindowStartSpeed;
	private boolean hasAccelWindow;
	private float gpsAccel;
	private boolean axisTrusted;
	private boolean stationary;
	private float displayAccel;
	private double distanceMeters;

	public SpeedEstimator() {
		filter.setBiasEnabled(false);
	}

	/**
	 * @param tNs         sample timestamp (elapsed realtime nanoseconds)
	 * @param linearAccel device-frame linear acceleration (m/s^2), gravity already removed
	 * @param gravity     device-frame gravity vector (m/s^2)
	 */
	public void onSensorSample(long tNs, float[] linearAccel, float[] gravity) {
		float g = Vec3.norm(gravity);
		if (g < MIN_GRAVITY_MPS2) {
			return;
		}
		gravityUnit[0] = gravity[0] / g;
		gravityUnit[1] = gravity[1] / g;
		gravityUnit[2] = gravity[2] / g;
		float along = Vec3.dot(linearAccel, gravityUnit);
		horizontal[0] = linearAccel[0] - along * gravityUnit[0];
		horizontal[1] = linearAccel[1] - along * gravityUnit[1];
		horizontal[2] = linearAccel[2] - along * gravityUnit[2];

		float dt = 0f;
		if (hasSample) {
			dt = (tNs - lastSampleNs) / NANOS_PER_SECOND;
			if (dt <= 0f) {
				return;
			}
			if (dt > MAX_SAMPLE_DT_SEC) {
				filter.coast(dt, axisTrusted);
				stationaryDetector.clear();
				dt = 0f;
			}
		}
		lastSampleNs = tNs;
		hasSample = true;

		axis.addSample(horizontal, dt, gravityUnit);
		syncAxisTrust();
		stationaryDetector.add(tNs, Vec3.norm(linearAccel));
		stationary = evaluateStationary(tNs);

		float accel = axisTrusted ? axis.project(horizontal) : fallbackAccel(tNs);
		filter.predict(tNs, accel, dt, axisTrusted);
		if (stationary) {
			if (axisTrusted) {
				filter.observeBias(accel);
			}
			filter.zeroVelocityUpdate();
		} else {
			distanceMeters += filter.getSpeed() * dt;
		}

		float corrected = axisTrusted ? accel - filter.getBias() : accel;
		displayAccel += ACCEL_DISPLAY_SMOOTHING * (corrected - displayAccel);
	}

	/**
	 * @param tNs      fix timestamp (elapsed realtime nanoseconds)
	 * @param speedMps GPS speed (m/s)
	 * @param sigmaMps reported speed accuracy as one standard deviation, NaN or 0 if unknown
	 */
	public void onGpsSpeed(long tNs, float speedMps, float sigmaMps) {
		if (Float.isNaN(speedMps) || speedMps < 0f) {
			return;
		}
		float sigma = (Float.isNaN(sigmaMps) || sigmaMps <= 0f)
				? DEFAULT_GPS_SPEED_SIGMA_MPS
				: Math.max(MIN_GPS_SPEED_SIGMA_MPS, Math.min(MAX_GPS_SPEED_SIGMA_MPS, sigmaMps));
		long tMeas = tNs - GPS_EXTRA_LATENCY_NS;

		updateGpsAccel(tMeas, speedMps);
		if (!hasSample && hasGps) {
			// No inertial data at all: integrate distance from GPS and let the filter uncertainty grow.
			float dt = (tMeas - lastGpsNs) / NANOS_PER_SECOND;
			if (dt > 0f && dt <= MAX_GPS_ACCEL_WINDOW_SEC) {
				distanceMeters += 0.5 * (speedMps + lastGpsSpeed) * dt;
				filter.coast(dt, false);
			}
		}
		axis.onGpsSpeed(tMeas, speedMps);
		syncAxisTrust();
		if (!stationary) {
			// While stationary the speed is pinned at zero with far better precision than GPS offers;
			// the fix still refreshes the staleness clock and the stationary gate below.
			filter.updateSpeed(tMeas, speedMps, sigma * sigma);
		}

		lastGpsNs = tMeas;
		lastGpsSpeed = speedMps;
		hasGps = true;
	}

	public float getSpeedMps() {
		return filter.getSpeed();
	}

	/** Bias-corrected forward acceleration, lightly smoothed for display (m/s^2). */
	public float getForwardAccelMps2() {
		return displayAccel;
	}

	public float getAccelBiasMps2() {
		return filter.getBias();
	}

	public boolean isStationary() {
		return stationary;
	}

	public boolean isForwardAxisKnown() {
		return axisTrusted;
	}

	public Mode getMode(long nowNs) {
		if (!hasGps) {
			return Mode.NO_FIX;
		}
		return nowNs - lastGpsNs > GPS_STALE_NS ? Mode.DEAD_RECKONING : Mode.GPS;
	}

	/** Seconds since the last accepted fix, NaN before the first one. */
	public float secondsSinceGps(long nowNs) {
		return hasGps ? Math.max(0f, (nowNs - lastGpsNs) / NANOS_PER_SECOND) : Float.NaN;
	}

	/** Returns the distance travelled since the previous call and resets the counter. */
	public double drainDistanceMeters() {
		double d = distanceMeters;
		distanceMeters = 0.0;
		return d;
	}

	private void updateGpsAccel(long tNs, float speedMps) {
		if (!hasAccelWindow) {
			startAccelWindow(tNs, speedMps);
			return;
		}
		float span = (tNs - accelWindowStartNs) / NANOS_PER_SECOND;
		if (span <= 0f || span > MAX_GPS_ACCEL_WINDOW_SEC) {
			gpsAccel = 0f;
			startAccelWindow(tNs, speedMps);
			return;
		}
		if (span < MIN_GPS_ACCEL_WINDOW_SEC) {
			return;
		}
		float a = (speedMps - accelWindowStartSpeed) / span;
		gpsAccel = Math.max(-MAX_GPS_ACCEL_MPS2, Math.min(MAX_GPS_ACCEL_MPS2, a));
		startAccelWindow(tNs, speedMps);
	}

	private void startAccelWindow(long tNs, float speedMps) {
		accelWindowStartNs = tNs;
		accelWindowStartSpeed = speedMps;
		hasAccelWindow = true;
	}

	/** Acceleration implied by recent GPS speed changes, held briefly, used while the axis is unknown. */
	private float fallbackAccel(long tNs) {
		return hasGps && tNs - lastGpsNs <= GPS_ACCEL_HOLD_NS ? gpsAccel : 0f;
	}

	private void syncAxisTrust() {
		boolean trusted = axis.isTrusted();
		if (trusted != axisTrusted) {
			axisTrusted = trusted;
			filter.setBiasEnabled(trusted);
		}
	}

	private boolean evaluateStationary(long tNs) {
		if (!stationaryDetector.isReady()) {
			return false;
		}
		float mean = stationaryDetector.getMean();
		float std = stationaryDetector.getStdDev();
		if (mean > STATIONARY_MEAN_MAX_MPS2) {
			return false;
		}
		boolean gpsRecent = hasGps && tNs - lastGpsNs <= GPS_STALE_NS;
		if (gpsRecent) {
			return lastGpsSpeed < STATIONARY_GPS_SPEED_MPS && std < STATIONARY_STD_WITH_GPS_MPS2;
		}
		return std < STATIONARY_STD_WITHOUT_GPS_MPS2;
	}
}
