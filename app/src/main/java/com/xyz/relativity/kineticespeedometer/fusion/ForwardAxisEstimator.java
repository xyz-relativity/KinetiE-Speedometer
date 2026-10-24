package com.xyz.relativity.kineticespeedometer.fusion;

/**
 * Learns the vehicle's forward axis in the device frame from the correlation between horizontal
 * acceleration and GPS speed changes.
 *
 * <p>Over each learning window (at least {@link #MIN_WINDOW_SEC}) the horizontal acceleration is
 * integrated into a vector S. If the GPS speed changed by dv over the same window, S should point
 * along the forward axis with the sign of dv. The least-squares direction is the normalised sum of
 * dv * S, weighted by dv^2, with exponential forgetting so a re-mounted phone is relearned. A large
 * change in the gravity direction, which means the phone was moved, resets the estimate at once.
 *
 * <p>No heading sensor is involved: the device frame is fixed to the vehicle as long as the phone
 * stays in its mount.
 */
public final class ForwardAxisEstimator {
	/** Minimum GPS speed change over a window for it to carry direction information. */
	static final float MIN_SPEED_CHANGE_MPS = 0.5f;
	/** Windows span at least this long so a high-rate GPS does not split accelerations into noise. */
	static final float MIN_WINDOW_SEC = 0.8f;
	static final float MAX_WINDOW_SEC = 3f;
	/** Sum of squared speed changes required before the axis is trusted. */
	static final float MIN_WEIGHT = 16f;
	/** Resultant length of the weighted sum; 1 means every window agreed on the direction. */
	static final float MIN_COHERENCE = 0.6f;
	static final float FORGETTING = 0.97f;
	static final float REMOUNT_ANGLE_DEG = 25f;
	private static final float REMOUNT_COS = (float) Math.cos(Math.toRadians(REMOUNT_ANGLE_DEG));
	private static final float GRAVITY_SMOOTHING = 0.02f;
	private static final float NANOS_PER_SECOND = 1e9f;

	private final float[] integral = new float[3];
	private final float[] accumulator = new float[3];
	private float weight;
	private float normalizer;
	private final float[] forward = new float[3];
	private boolean trusted;
	private float coherence;

	private final float[] gravityReference = new float[3];
	private boolean hasGravityReference;
	private final float[] gravitySmoothed = new float[3];
	private boolean hasGravitySmoothed;
	private final float[] scratch = new float[3];

	private long windowStartNs;
	private float windowStartSpeed;
	private boolean hasWindow;

	/**
	 * @param horizontalAccel device-frame linear acceleration with its gravity component removed
	 * @param dtSec           sample interval, 0 to skip integration
	 * @param gravityUnit     unit gravity vector in the device frame
	 */
	public void addSample(float[] horizontalAccel, float dtSec, float[] gravityUnit) {
		if (dtSec > 0f) {
			integral[0] += horizontalAccel[0] * dtSec;
			integral[1] += horizontalAccel[1] * dtSec;
			integral[2] += horizontalAccel[2] * dtSec;
		}
		trackGravity(gravityUnit);
	}

	public void onGpsSpeed(long tNs, float speedMps) {
		if (!hasWindow) {
			startWindow(tNs, speedMps);
			return;
		}
		float span = (tNs - windowStartNs) / NANOS_PER_SECOND;
		if (span <= 0f || span > MAX_WINDOW_SEC) {
			startWindow(tNs, speedMps);
			return;
		}
		if (span < MIN_WINDOW_SEC) {
			return; // keep integrating until the window is long enough
		}
		learn(speedMps - windowStartSpeed);
		startWindow(tNs, speedMps);
	}

	/** Forward component of a horizontal acceleration, or 0 while the axis is not trusted. */
	public float project(float[] horizontalAccel) {
		return trusted ? Vec3.dot(horizontalAccel, forward) : 0f;
	}

	public boolean isTrusted() {
		return trusted;
	}

	public float getCoherence() {
		return coherence;
	}

	public float getWeight() {
		return weight;
	}

	/** Copies the current forward unit vector (device frame) into dst. Only meaningful when trusted. */
	public void getForward(float[] dst) {
		Vec3.copy(forward, dst);
	}

	public void reset() {
		Vec3.zero(integral);
		Vec3.zero(accumulator);
		weight = 0f;
		normalizer = 0f;
		coherence = 0f;
		trusted = false;
		hasGravityReference = false;
		hasWindow = false;
	}

	private void startWindow(long tNs, float speedMps) {
		windowStartNs = tNs;
		windowStartSpeed = speedMps;
		hasWindow = true;
		Vec3.zero(integral);
	}

	private void learn(float dv) {
		float magnitude = Vec3.norm(integral);
		if (Math.abs(dv) < MIN_SPEED_CHANGE_MPS || magnitude < 1e-3f) {
			return;
		}
		for (int i = 0; i < 3; i++) {
			accumulator[i] = FORGETTING * accumulator[i] + dv * integral[i];
		}
		weight = FORGETTING * weight + dv * dv;
		normalizer = FORGETTING * normalizer + Math.abs(dv) * magnitude;
		if (!hasGravityReference && hasGravitySmoothed && Vec3.normalize(gravitySmoothed, gravityReference)) {
			hasGravityReference = true;
		}
		recompute();
	}

	private void recompute() {
		float magnitude = Vec3.norm(accumulator);
		coherence = normalizer > 0f ? magnitude / normalizer : 0f;
		boolean ok = weight >= MIN_WEIGHT && coherence >= MIN_COHERENCE && magnitude > 0f;
		if (ok) {
			Vec3.normalize(accumulator, forward);
		}
		trusted = ok;
	}

	private void trackGravity(float[] gravityUnit) {
		if (!hasGravitySmoothed) {
			Vec3.copy(gravityUnit, gravitySmoothed);
			hasGravitySmoothed = true;
			return;
		}
		for (int i = 0; i < 3; i++) {
			gravitySmoothed[i] += GRAVITY_SMOOTHING * (gravityUnit[i] - gravitySmoothed[i]);
		}
		if (!hasGravityReference || !Vec3.normalize(gravitySmoothed, scratch)) {
			return;
		}
		if (Vec3.dot(scratch, gravityReference) < REMOUNT_COS) {
			reset();
		}
	}
}
