package com.xyz.relativity.kineticespeedometer.fusion;

/**
 * Two-state Kalman filter over forward speed and forward-acceleration bias.
 *
 * <p>State x = [v, b]: v is the speed along the vehicle's forward axis (m/s) and b is the
 * bias of the measured forward acceleration (m/s^2). Prediction integrates
 * v' = v + (a - b) * dt at sensor rate. GPS speed fixes are delayed measurements: the
 * innovation is computed against the speed that was predicted at the fix timestamp (kept
 * in a short history) and the correction is propagated to the present, which removes the
 * sawtooth a plain blend produces while accelerating. Zero-velocity updates and direct
 * bias observations are applied while the vehicle is known to be stationary.
 *
 * <p>Pure Java, single threaded, no Android dependencies.
 */
public final class SpeedKalmanFilter {
	/** Velocity random-walk density (m^2/s^3) while the acceleration input comes from the inertial sensor. */
	static final float VELOCITY_NOISE_SENSOR = 0.04f;
	/** Velocity random-walk density (m^2/s^3) while the acceleration input is only a GPS-derived guess. */
	static final float VELOCITY_NOISE_FALLBACK = 0.3f;
	/** Bias random-walk density ((m/s^2)^2 / s). */
	static final float BIAS_NOISE = 2.5e-4f;
	static final float INITIAL_BIAS_VARIANCE = 0.3f * 0.3f;
	static final float ZERO_VELOCITY_VARIANCE = 0.05f * 0.05f;
	static final float BIAS_OBSERVATION_VARIANCE = 0.5f * 0.5f;
	/** Innovations beyond this many sigmas are treated as GPS glitches, up to a few in a row. */
	static final float OUTLIER_SIGMAS = 10f;
	static final int MAX_CONSECUTIVE_OUTLIERS = 2;
	/** Measurements older than this relative to the newest prediction are applied without delay compensation. */
	static final long MAX_MEASUREMENT_AGE_NS = 3_000_000_000L;
	private static final int HISTORY_CAPACITY = 1024;
	private static final float NANOS_PER_SECOND = 1e9f;

	private float speed;
	private float bias;
	private float p00;
	private float p01;
	private float p11;
	private boolean initialized;
	private boolean biasEnabled;
	private long lastPredictNs;
	private boolean hasPredicted;
	private int consecutiveOutliers;

	private final long[] historyTime = new long[HISTORY_CAPACITY];
	private final float[] historySpeed = new float[HISTORY_CAPACITY];
	private int historyHead;
	private int historySize;

	public SpeedKalmanFilter() {
		setBiasEnabled(true);
	}

	/**
	 * Integrates one forward-acceleration sample.
	 *
	 * @param tNs         sample timestamp (elapsed realtime nanoseconds)
	 * @param accel       measured forward acceleration (m/s^2)
	 * @param dtSec       time since the previous sample; 0 only records the timestamp
	 * @param sensorAccel true when accel comes from the inertial sensor, false for a GPS-derived guess
	 */
	public void predict(long tNs, float accel, float dtSec, boolean sensorAccel) {
		if (dtSec > 0f) {
			speed += (accel - bias) * dtSec;
			if (speed < 0f) {
				speed = 0f;
			}
			float q = sensorAccel ? VELOCITY_NOISE_SENSOR : VELOCITY_NOISE_FALLBACK;
			// P = F P F^T + Q with F = [[1, -dt], [0, 1]]; order matters, each line uses the old values.
			p00 = p00 - 2f * dtSec * p01 + dtSec * dtSec * p11 + q * dtSec;
			p01 = p01 - dtSec * p11;
			if (biasEnabled) {
				p11 += BIAS_NOISE * dtSec;
			}
		}
		lastPredictNs = tNs;
		hasPredicted = true;
		pushHistory(tNs, speed);
	}

	/** Grows the uncertainty after a gap without samples (app paused), without integrating anything. */
	public void coast(float gapSec, boolean sensorAccel) {
		if (gapSec <= 0f) {
			return;
		}
		float q = sensorAccel ? VELOCITY_NOISE_SENSOR : VELOCITY_NOISE_FALLBACK;
		p00 += q * gapSec + gapSec * gapSec * p11;
		if (biasEnabled) {
			p11 += BIAS_NOISE * gapSec;
		}
	}

	/**
	 * Applies a GPS speed measurement taken at tMeasNs.
	 *
	 * @param tMeasNs       time the measurement refers to (elapsed realtime nanoseconds)
	 * @param measuredSpeed measured speed (m/s)
	 * @param variance      measurement variance ((m/s)^2)
	 * @return false if the measurement was rejected as an outlier
	 */
	public boolean updateSpeed(long tMeasNs, float measuredSpeed, float variance) {
		if (measuredSpeed < 0f) {
			measuredSpeed = 0f;
		}
		if (!initialized) {
			speed = measuredSpeed;
			p00 = variance;
			p01 = 0f;
			initialized = true;
			for (int i = 0; i < historySize; i++) {
				historySpeed[historyIndex(i)] = speed;
			}
			return true;
		}

		float reference = hasPredicted ? speedAt(tMeasNs) : Float.NaN;
		float lagSec;
		if (Float.isNaN(reference)) {
			reference = speed;
			lagSec = 0f;
			if (hasPredicted) {
				tMeasNs = lastPredictNs;
			}
		} else {
			lagSec = Math.max(0f, (lastPredictNs - tMeasNs) / NANOS_PER_SECOND);
		}

		float innovation = measuredSpeed - reference;
		float s = p00 + variance;
		if (innovation * innovation > OUTLIER_SIGMAS * OUTLIER_SIGMAS * s
				&& consecutiveOutliers < MAX_CONSECUTIVE_OUTLIERS) {
			consecutiveOutliers++;
			return false;
		}
		consecutiveOutliers = 0;

		float k0 = p00 / s;
		float k1 = p01 / s;
		float dv = k0 * innovation;
		float db = k1 * innovation;

		// Propagate the correction made at tMeas to the present: a bias change alters the slope since then.
		speed += dv - db * lagSec;
		if (speed < 0f) {
			speed = 0f;
		}
		bias += db;
		for (int i = historySize - 1; i >= 0; i--) {
			int idx = historyIndex(i);
			if (historyTime[idx] < tMeasNs) {
				break;
			}
			float age = (historyTime[idx] - tMeasNs) / NANOS_PER_SECOND;
			historySpeed[idx] = Math.max(0f, historySpeed[idx] + dv - db * age);
		}

		float n00 = (1f - k0) * p00;
		float n01 = (1f - k0) * p01;
		float n11 = p11 - k1 * p01;
		p00 = n00;
		p01 = n01;
		p11 = n11;
		return true;
	}

	/** Pins the speed to zero; the innovation also nudges the bias in the direction that explains the drift. */
	public void zeroVelocityUpdate() {
		if (!initialized) {
			initialized = true;
			p00 = ZERO_VELOCITY_VARIANCE;
			p01 = 0f;
		} else {
			float innovation = -speed;
			float s = p00 + ZERO_VELOCITY_VARIANCE;
			float k0 = p00 / s;
			float k1 = p01 / s;
			bias += k1 * innovation;
			float n00 = (1f - k0) * p00;
			float n01 = (1f - k0) * p01;
			float n11 = p11 - k1 * p01;
			p00 = n00;
			p01 = n01;
			p11 = n11;
		}
		speed = 0f;
		if (historySize > 0) {
			historySpeed[historyIndex(historySize - 1)] = 0f;
		}
	}

	/** While stationary the measured forward acceleration is a direct observation of the bias. */
	public void observeBias(float measuredAccel) {
		if (!biasEnabled) {
			return;
		}
		float innovation = measuredAccel - bias;
		float s = p11 + BIAS_OBSERVATION_VARIANCE;
		float k0 = p01 / s;
		float k1 = p11 / s;
		speed = Math.max(0f, speed + k0 * innovation);
		bias += k1 * innovation;
		float n00 = p00 - k0 * p01;
		float n01 = p01 - k0 * p11;
		float n11 = (1f - k1) * p11;
		p00 = n00;
		p01 = n01;
		p11 = n11;
	}

	/**
	 * Enables or disables bias estimation. Disabled, the bias is held at zero, which is the right
	 * thing while the forward axis is unknown and the acceleration input is only a guess. Either
	 * way the bias estimate is reset.
	 */
	public void setBiasEnabled(boolean enabled) {
		biasEnabled = enabled;
		bias = 0f;
		p01 = 0f;
		p11 = enabled ? INITIAL_BIAS_VARIANCE : 0f;
	}

	public float getSpeed() {
		return speed;
	}

	public float getBias() {
		return bias;
	}

	public float getSpeedVariance() {
		return p00;
	}

	public float getBiasVariance() {
		return p11;
	}

	public boolean isInitialized() {
		return initialized;
	}

	public boolean isBiasEnabled() {
		return biasEnabled;
	}

	// --- speed history for delayed measurements ---

	private void pushHistory(long tNs, float value) {
		historyTime[historyHead] = tNs;
		historySpeed[historyHead] = value;
		historyHead = (historyHead + 1) % HISTORY_CAPACITY;
		if (historySize < HISTORY_CAPACITY) {
			historySize++;
		}
	}

	/** Maps 0 (oldest) .. historySize-1 (newest) to a ring index. */
	private int historyIndex(int i) {
		return (historyHead - historySize + i + HISTORY_CAPACITY) % HISTORY_CAPACITY;
	}

	/** Speed predicted at tMeasNs, or NaN when the present estimate should be used instead. */
	private float speedAt(long tMeasNs) {
		if (historySize == 0) {
			return Float.NaN;
		}
		int newest = historyIndex(historySize - 1);
		if (tMeasNs >= historyTime[newest]) {
			return Float.NaN;
		}
		if (historyTime[newest] - tMeasNs > MAX_MEASUREMENT_AGE_NS) {
			return Float.NaN; // too old, or the two clocks do not match
		}
		int oldest = historyIndex(0);
		if (tMeasNs <= historyTime[oldest]) {
			return historySpeed[oldest];
		}
		int lo = 0;
		int hi = historySize - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (historyTime[historyIndex(mid)] <= tMeasNs) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		int i0 = historyIndex(lo);
		int i1 = historyIndex(lo + 1);
		long t0 = historyTime[i0];
		long t1 = historyTime[i1];
		float frac = t1 > t0 ? (float) (tMeasNs - t0) / (float) (t1 - t0) : 0f;
		return historySpeed[i0] + frac * (historySpeed[i1] - historySpeed[i0]);
	}
}
