package com.xyz.relativity.kineticespeedometer.fusion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * End-to-end simulation of a drive: phone in a tilted mount, biased sensor, lateral acceleration
 * from curves, GPS speed delivered 0.6 s late with noise, and a 12 s GPS outage at cruise.
 */
public class SpeedEstimatorTest {
	private static final int RATE = 50;
	private static final long STEP = 1_000_000_000L / RATE;
	private static final float DT = 1f / RATE;
	private static final long GPS_LATENCY_NS = 600_000_000L;
	private static final int GPS_LATENCY_SAMPLES = (int) (GPS_LATENCY_NS / STEP);

	/** 45 degree mount: gravity splits between device y and z; forward is the perpendicular in that plane. */
	private static final float[] GRAVITY = {0f, 6.937f, 6.937f};
	private static final float[] FORWARD = {0f, 0.70711f, -0.70711f};
	private static final float[] LATERAL = {1f, 0f, 0f};
	private static final float BIAS_FORWARD = 0.15f;
	private static final float BIAS_LATERAL = 0.10f;

	private static float profile(float t) {
		if (t < 5f) return 0f;
		if (t < 15f) return 1.5f;
		if (t < 35f) return 0f;
		if (t < 43f) return -1.5f;
		if (t < 51f) return 1.5f;
		if (t < 70f) return 0f;
		if (t < 80f) return -1.5f;
		if (t < 90f) return 0f;
		if (t < 100f) return 2f;
		if (t < 115f) return 0f;
		if (t < 125f) return -2f;
		return 0f;
	}

	private static boolean gpsAvailable(float t) {
		return t < 102f || t >= 114f;
	}

	private static final class Drive {
		int samples;
		float[] truth;
		float[] estimate;
		boolean[] stationary;
		boolean[] axisKnown;
		double distanceTruth;
		double distanceEstimate;
	}

	private static Drive simulate(int seconds, long seed) {
		SpeedEstimator estimator = new SpeedEstimator();
		Random rnd = new Random(seed);
		Drive d = new Drive();
		d.samples = seconds * RATE;
		d.truth = new float[d.samples];
		d.estimate = new float[d.samples];
		d.stationary = new boolean[d.samples];
		d.axisKnown = new boolean[d.samples];

		float[] linear = new float[3];
		float[] gravity = new float[3];
		float speed = 0f;
		long t = 0;
		for (int i = 0; i < d.samples; i++) {
			t += STEP;
			float tSec = t / 1e9f;
			float a = profile(tSec);
			float next = speed + a * DT;
			if (next <= 0f && a < 0f) {
				a = speed > 0f ? -speed / DT : 0f;
				next = 0f;
			}
			speed = next;
			d.truth[i] = speed;
			d.distanceTruth += speed * DT;

			boolean moving = speed > 0.01f;
			float vibration = moving ? 0.3f : 0.04f;
			float lateral = moving ? (float) Math.sin(tSec * 0.5) : 0f;
			for (int k = 0; k < 3; k++) {
				linear[k] = (a + BIAS_FORWARD) * FORWARD[k]
						+ (lateral + BIAS_LATERAL) * LATERAL[k]
						+ (float) rnd.nextGaussian() * vibration;
				gravity[k] = GRAVITY[k] + (float) rnd.nextGaussian() * 0.02f;
			}
			estimator.onSensorSample(t, linear, gravity);

			if (i % RATE == RATE - 1 && gpsAvailable(tSec)) {
				float measured = d.truth[Math.max(0, i - GPS_LATENCY_SAMPLES)];
				float z = Math.max(0f, measured + (float) rnd.nextGaussian() * 0.2f);
				estimator.onGpsSpeed(t - GPS_LATENCY_NS, z, 0.3f);
			}

			d.estimate[i] = estimator.getSpeedMps();
			d.stationary[i] = estimator.isStationary();
			d.axisKnown[i] = estimator.isForwardAxisKnown();
			d.distanceEstimate += estimator.drainDistanceMeters();
		}
		return d;
	}

	@Test
	public void learnsTheAxisWithinTheFirstMinute() {
		Drive d = simulate(135, 1234);
		assertTrue(d.axisKnown[60 * RATE]);
	}

	@Test
	public void tracksSpeedWhileGpsIsAvailable() {
		Drive d = simulate(135, 1234);
		double sumSq = 0;
		int count = 0;
		float maxError = 0f;
		for (int i = 60 * RATE; i < d.samples; i++) {
			float tSec = (float) (i + 1) / RATE;
			if (tSec >= 102f && tSec < 116f) {
				continue; // outage and two seconds of recovery
			}
			float err = Math.abs(d.estimate[i] - d.truth[i]);
			sumSq += err * err;
			count++;
			maxError = Math.max(maxError, err);
		}
		double rms = Math.sqrt(sumSq / count);
		assertTrue("rms " + rms, rms < 0.5);
		assertTrue("max " + maxError, maxError < 1.5f);
	}

	@Test
	public void holdsSpeedThroughAGpsOutage() {
		Drive d = simulate(135, 1234);
		float maxError = 0f;
		for (int i = 102 * RATE; i < 114 * RATE; i++) {
			maxError = Math.max(maxError, Math.abs(d.estimate[i] - d.truth[i]));
		}
		assertTrue("max error during outage " + maxError, maxError < 2f);
	}

	@Test
	public void detectsTheFinalStop() {
		Drive d = simulate(135, 1234);
		int i = 133 * RATE;
		assertTrue(d.stationary[i]);
		assertEquals(0f, d.estimate[i], 0f);
	}

	@Test
	public void distanceMatchesTheDrive() {
		Drive d = simulate(135, 1234);
		double relative = Math.abs(d.distanceEstimate - d.distanceTruth) / d.distanceTruth;
		assertTrue("truth " + d.distanceTruth + " estimate " + d.distanceEstimate, relative < 0.03);
	}

	@Test
	public void parkedCarAccumulatesNoDistance() {
		SpeedEstimator estimator = new SpeedEstimator();
		Random rnd = new Random(5);
		float[] linear = new float[3];
		long t = 0;
		for (int i = 0; i < 60 * RATE; i++) {
			t += STEP;
			for (int k = 0; k < 3; k++) {
				linear[k] = BIAS_FORWARD * FORWARD[k] + BIAS_LATERAL * LATERAL[k] + (float) rnd.nextGaussian() * 0.04f;
			}
			estimator.onSensorSample(t, linear, GRAVITY);
			if (i % RATE == RATE - 1) {
				estimator.onGpsSpeed(t - GPS_LATENCY_NS, Math.max(0f, (float) rnd.nextGaussian() * 0.25f), 0.3f);
			}
		}
		assertTrue(estimator.isStationary());
		assertEquals(0f, estimator.getSpeedMps(), 0f);
		assertTrue(estimator.drainDistanceMeters() < 1.0);
	}

	/**
	 * The car pitches nose-up by 6 degrees over 3 s (a hill) while cruising. Without a gyro the
	 * tilted gravity leaks into the forward axis and the speed drifts; with the gyro the gravity
	 * estimate follows the tilt and the speed holds.
	 */
	@Test
	public void gyroKeepsGravityOnHills() {
		float driftWithout = hillDrift(false);
		float driftWith = hillDrift(true);
		assertTrue("without gyro " + driftWithout, driftWithout > 1.0f);
		assertTrue("with gyro " + driftWith, driftWith < 0.3f);
	}

	private static float hillDrift(boolean useGyro) {
		// The simulated GPS has no latency, so the estimator must not assume any.
		SpeedEstimator.Tuning tuning = new SpeedEstimator.Tuning();
		tuning.gpsExtraLatencySec = 0f;
		SpeedEstimator estimator = new SpeedEstimator(tuning);
		Random rnd = new Random(99);
		// Phone flat: gravity along device z, forward along device y. Learn the axis first.
		float[] forward = {0f, 1f, 0f};
		float[] rateAxis = {1f, 0f, 0f}; // pitching about the lateral (device x) axis
		float[] linear = new float[3];
		float[] gravity = new float[3];
		float[] rate = new float[3];
		float pitch = 0f;
		float speed = 0f;
		long t = 0;
		int samples = 0;
		for (int i = 0; i < 80 * RATE; i++) {
			t += STEP;
			float tSec = t / 1e9f;
			// accelerate to 15 m/s, brake to 6, accelerate back to 15, then cruise through the hill
			float a = tSec < 10f ? 0f : tSec < 20f ? 1.5f : tSec < 30f ? 0f : tSec < 36f ? -1.5f : tSec < 42f ? 1.5f : 0f;
			speed = Math.max(0f, speed + a * DT);
			boolean hill = tSec >= 60f && tSec < 63f;
			float pitchRate = hill ? (float) Math.toRadians(2.0) : 0f;
			pitch += pitchRate * DT;
			// gravity in the device frame tilts toward -y as the nose goes up
			gravity[0] = 0f;
			gravity[1] = -9.81f * (float) Math.sin(pitch);
			gravity[2] = 9.81f * (float) Math.cos(pitch);
			// the platform reports the true linear acceleration plus road vibration; the estimator sums both anyway
			for (int k = 0; k < 3; k++) {
				linear[k] = a * forward[k] + (float) rnd.nextGaussian() * 0.2f;
			}
			if (useGyro) {
				rate[0] = -pitchRate * rateAxis[0];
				rate[1] = 0f;
				rate[2] = 0f;
				estimator.onGyroSample(t, rate);
			}
			estimator.onSensorSample(t, linear, gravity);
			if (i % RATE == RATE - 1 && tSec < 58f) {
				estimator.onGpsSpeed(t, speed, 0.1f);
			}
			samples++;
		}
		// GPS was withheld from 58 s on; the car kept cruising at 'speed'
		return Math.abs(estimator.getSpeedMps() - speed);
	}

	@Test
	public void reportsModeFromGpsAge() {
		SpeedEstimator estimator = new SpeedEstimator();
		assertEquals(SpeedEstimator.Mode.NO_FIX, estimator.getMode(0));
		estimator.onGpsSpeed(10_000_000_000L, 5f, 0.3f);
		assertEquals(SpeedEstimator.Mode.GPS, estimator.getMode(11_000_000_000L));
		assertEquals(SpeedEstimator.Mode.DEAD_RECKONING, estimator.getMode(20_000_000_000L));
	}
}
