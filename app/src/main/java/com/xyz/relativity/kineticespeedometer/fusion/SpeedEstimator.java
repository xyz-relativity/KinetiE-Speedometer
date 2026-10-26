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

	/** Knobs worth tuning against recorded drives; the defaults come from such recordings. */
	public static final class Tuning {
		/**
		 * Time constant of the estimator's own gravity estimate. The platform's linear-acceleration
		 * sensor absorbs sustained acceleration into its gravity estimate, so it loses most of what a
		 * car does; a slow estimate of our own over the raw accelerometer keeps it.
		 */
		public float gravityTimeConstantSec = 15f;
		/**
		 * Updates of the gravity estimate pause while the horizontal acceleration, smoothed over about a
		 * second, exceeds this: ordinary driving accelerations must never be absorbed as gravity.
		 */
		public float gravityUpdateMaxAccelMps2 = 0.8f;
		/** Smoothing applied to the horizontal acceleration before the gate above, in seconds. */
		public float gravityGateSmoothingSec = 1f;
		/** While the car stands still the true acceleration is zero, so gravity can be learned this fast. */
		public float gravityStationaryTimeConstantSec = 5f;
		/** Extra age assumed for every fix beyond its own timestamp. */
		public float gpsExtraLatencySec = 0.2f;
		/** Acceleration spread under which a car with a near-zero GPS speed counts as standing still. */
		public float stationaryStdWithGpsMps2 = 0.22f;
		/** Direction agreement required before the forward axis is trusted. */
		public float axisMinCoherence = ForwardAxisEstimator.DEFAULT_MIN_COHERENCE;
		/** Kalman velocity random-walk density with inertial input (m^2/s^3). Tuned on recorded drives. */
		public float velocityNoiseSensor = 0.2f;
		/** Kalman bias random-walk density ((m/s^2)^2/s). Tuned on recorded drives. */
		public float biasNoise = 2e-3f;
	}

	public static final float DEFAULT_GPS_SPEED_SIGMA_MPS = 0.5f;
	public static final float MIN_GPS_SPEED_SIGMA_MPS = 0.15f;
	public static final float MAX_GPS_SPEED_SIGMA_MPS = 5f;
	/** Without a fix for this long the estimate runs on dead reckoning alone. */
	public static final long GPS_STALE_NS = 3_000_000_000L;

	static final float STATIONARY_GPS_SPEED_MPS = 0.8f;
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

	private final Tuning tuning;
	private final long gpsExtraLatencyNs;
	private final SpeedKalmanFilter filter;
	private final ForwardAxisEstimator axis;
	private final StationaryDetector stationaryDetector = new StationaryDetector();

	private final float[] rawAccel = new float[3];
	private final float[] slowGravity = new float[3];
	private boolean hasSlowGravity;
	private final float[] gyroDelta = new float[3];
	private final float[] gravityDelta = new float[3];
	private long lastGyroNs;
	private boolean hasGyro;
	private final float[] gravityUnit = new float[3];
	private final float[] accel = new float[3];
	private final float[] horizontal = new float[3];
	private final float[] horizontalSmooth = new float[3];

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
		this(new Tuning());
	}

	public SpeedEstimator(Tuning tuning) {
		this.tuning = tuning;
		this.gpsExtraLatencyNs = (long) (tuning.gpsExtraLatencySec * NANOS_PER_SECOND);
		this.filter = new SpeedKalmanFilter(tuning.velocityNoiseSensor, tuning.biasNoise);
		this.axis = new ForwardAxisEstimator(tuning.axisMinCoherence);
		filter.setBiasEnabled(false);
	}

	/**
	 * Rotates the gravity estimate with the measured rotation, so it follows road pitch and mount
	 * shifts within a sample instead of over the slow accelerometer average. Optional: without gyro
	 * samples the estimate relies on the slow average alone and hills leak into the speed.
	 *
	 * @param tNs      sample timestamp (elapsed realtime nanoseconds)
	 * @param rateRads device-frame angular rate (rad/s), right-handed, as the gyroscope reports it
	 */
	public void onGyroSample(long tNs, float[] rateRads) {
		if (hasGyro && hasSlowGravity) {
			float dt = (tNs - lastGyroNs) / NANOS_PER_SECOND;
			if (dt > 0f && dt <= MAX_SAMPLE_DT_SEC) {
				// A world-fixed vector seen from a rotating frame: dg/dt = -rate x g. The first-order step
				// stretches the vector slightly; restore its length so fast turns do not inflate it.
				float length = Vec3.norm(slowGravity);
				Vec3.cross(rateRads, slowGravity, gyroDelta);
				slowGravity[0] -= gyroDelta[0] * dt;
				slowGravity[1] -= gyroDelta[1] * dt;
				slowGravity[2] -= gyroDelta[2] * dt;
				float stretched = Vec3.norm(slowGravity);
				if (stretched > 0f) {
					float scale = length / stretched;
					slowGravity[0] *= scale;
					slowGravity[1] *= scale;
					slowGravity[2] *= scale;
				}
			}
		}
		lastGyroNs = tNs;
		hasGyro = true;
	}

	/**
	 * The two vectors are the platform's split of the raw accelerometer; they are added back
	 * together here because the platform's gravity estimate swallows sustained acceleration. The
	 * fused gravity only seeds the estimator's own, slower gravity estimate.
	 *
	 * @param tNs         sample timestamp (elapsed realtime nanoseconds)
	 * @param linearAccel device-frame linear acceleration as the platform reports it (m/s^2)
	 * @param gravity     device-frame gravity as the platform reports it (m/s^2)
	 */
	public void onSensorSample(long tNs, float[] linearAccel, float[] gravity) {
		if (Vec3.norm(gravity) < MIN_GRAVITY_MPS2) {
			return;
		}
		rawAccel[0] = linearAccel[0] + gravity[0];
		rawAccel[1] = linearAccel[1] + gravity[1];
		rawAccel[2] = linearAccel[2] + gravity[2];

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

		if (!hasSlowGravity || Vec3.norm(slowGravity) < MIN_GRAVITY_MPS2) {
			Vec3.copy(gravity, slowGravity);
			hasSlowGravity = true;
		}
		float g = Vec3.norm(slowGravity);
		gravityUnit[0] = slowGravity[0] / g;
		gravityUnit[1] = slowGravity[1] / g;
		gravityUnit[2] = slowGravity[2] / g;
		accel[0] = rawAccel[0] - slowGravity[0];
		accel[1] = rawAccel[1] - slowGravity[1];
		accel[2] = rawAccel[2] - slowGravity[2];
		float along = Vec3.dot(accel, gravityUnit);
		horizontal[0] = accel[0] - along * gravityUnit[0];
		horizontal[1] = accel[1] - along * gravityUnit[1];
		horizontal[2] = accel[2] - along * gravityUnit[2];

		stationaryDetector.add(tNs, Vec3.norm(linearAccel));
		stationary = evaluateStationary(tNs);

		// Gravity is learned fast at standstill, where the true acceleration is zero, and slowly while
		// cruising, gated on a smoothed acceleration so driving accelerations are never absorbed. The
		// gyro, when present, carries the direction through pitch changes in between.
		if (dt > 0f) {
			float ks = Math.min(1f, dt / tuning.gravityGateSmoothingSec);
			horizontalSmooth[0] += ks * (horizontal[0] - horizontalSmooth[0]);
			horizontalSmooth[1] += ks * (horizontal[1] - horizontalSmooth[1]);
			horizontalSmooth[2] += ks * (horizontal[2] - horizontalSmooth[2]);
			float k = 0f;
			if (stationary) {
				k = Math.min(1f, dt / tuning.gravityStationaryTimeConstantSec);
			} else if (Vec3.norm(horizontalSmooth) < tuning.gravityUpdateMaxAccelMps2) {
				k = Math.min(1f, dt / tuning.gravityTimeConstantSec);
			}
			if (k > 0f) {
				gravityDelta[0] = k * (rawAccel[0] - slowGravity[0]);
				gravityDelta[1] = k * (rawAccel[1] - slowGravity[1]);
				gravityDelta[2] = k * (rawAccel[2] - slowGravity[2]);
				slowGravity[0] += gravityDelta[0];
				slowGravity[1] += gravityDelta[1];
				slowGravity[2] += gravityDelta[2];
				// The measured forward acceleration drops by the correction's forward component; move the
				// Kalman bias with it so the fused speed does not jump and GPS keeps the last word.
				if (axisTrusted) {
					filter.shiftBias(-axis.project(gravityDelta));
				}
			}
		}

		axis.addSample(horizontal, dt, gravityUnit);
		syncAxisTrust();

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
		long tMeas = tNs - gpsExtraLatencyNs;

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

	/** Copies the learned forward axis (device frame) into dst; false while it is not trusted. */
	public boolean copyForwardAxis(float[] dst) {
		if (!axisTrusted) {
			return false;
		}
		axis.getForward(dst);
		return true;
	}

	/** Starts from a forward axis learned on an earlier run, so the inertial path works from the first fix. */
	public void restoreForwardAxis(float[] forwardUnit) {
		axis.restore(forwardUnit);
		syncAxisTrust();
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
			return lastGpsSpeed < STATIONARY_GPS_SPEED_MPS && std < tuning.stationaryStdWithGpsMps2;
		}
		return std < STATIONARY_STD_WITHOUT_GPS_MPS2;
	}
}
