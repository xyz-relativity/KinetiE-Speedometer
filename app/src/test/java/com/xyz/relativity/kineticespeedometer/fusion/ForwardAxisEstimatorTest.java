package com.xyz.relativity.kineticespeedometer.fusion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class ForwardAxisEstimatorTest {
	private static final long STEP = 20_000_000L;
	private static final float DT = 0.02f;
	private static final float[] GRAVITY = {0f, 0f, 1f};
	private static final float[] FORWARD = {0.6f, 0.8f, 0f};
	private static final float[] LATERAL = {-0.8f, 0.6f, 0f};

	/** Accelerate 5 s, cruise 5 s, brake 5 s, stand 5 s, repeat. */
	private static float trueAccel(float t) {
		float phase = t % 20f;
		if (phase < 5f) return 2f;
		if (phase < 10f) return 0f;
		if (phase < 15f) return -2f;
		return 0f;
	}

	/** Drives the estimator for the given number of seconds and returns the simulated end time. */
	private static long drive(ForwardAxisEstimator axis, long startNs, int seconds, Random rnd) {
		float[] sample = new float[3];
		float speed = 0f;
		long t = startNs;
		for (int i = 1; i <= 50 * seconds; i++) {
			t += STEP;
			float a = trueAccel((t - startNs) / 1e9f);
			speed = Math.max(0f, speed + a * DT);
			if (speed == 0f && a < 0f) {
				a = 0f;
			}
			float lateral = (float) rnd.nextGaussian();
			for (int k = 0; k < 3; k++) {
				sample[k] = a * FORWARD[k] + lateral * LATERAL[k] + (float) rnd.nextGaussian() * 0.3f;
			}
			axis.addSample(sample, DT, GRAVITY);
			if (i % 50 == 0) {
				axis.onGpsSpeed(t, speed + (float) rnd.nextGaussian() * 0.1f);
			}
		}
		return t;
	}

	@Test
	public void learnsForwardAxisDespiteLateralNoise() {
		ForwardAxisEstimator axis = new ForwardAxisEstimator();
		drive(axis, 0, 80, new Random(7));
		assertTrue("weight " + axis.getWeight() + " coherence " + axis.getCoherence(), axis.isTrusted());
		float[] learned = new float[3];
		axis.getForward(learned);
		assertTrue("dot " + Vec3.dot(learned, FORWARD), Vec3.dot(learned, FORWARD) > 0.98f);
	}

	@Test
	public void projectionIsZeroUntilTrusted() {
		ForwardAxisEstimator axis = new ForwardAxisEstimator();
		float[] sample = {1f, 1f, 0f};
		assertFalse(axis.isTrusted());
		assertTrue(axis.project(sample) == 0f);
	}

	@Test
	public void gravityChangeResetsTheAxis() {
		ForwardAxisEstimator axis = new ForwardAxisEstimator();
		long t = drive(axis, 0, 80, new Random(3));
		assertTrue(axis.isTrusted());
		float[] moved = {1f, 0f, 0f};
		float[] quiet = {0f, 0f, 0f};
		for (int i = 0; i < 50 * 3; i++) {
			t += STEP;
			axis.addSample(quiet, DT, moved);
		}
		assertFalse(axis.isTrusted());
	}

	@Test
	public void noiseWithoutSpeedChangesIsNeverTrusted() {
		ForwardAxisEstimator axis = new ForwardAxisEstimator();
		Random rnd = new Random(11);
		float[] sample = new float[3];
		long t = 0;
		for (int i = 1; i <= 50 * 120; i++) {
			t += STEP;
			for (int k = 0; k < 3; k++) {
				sample[k] = (float) rnd.nextGaussian();
			}
			axis.addSample(sample, DT, GRAVITY);
			if (i % 50 == 0) {
				axis.onGpsSpeed(t, 10f + (float) rnd.nextGaussian() * 0.3f);
			}
		}
		assertFalse(axis.isTrusted());
	}
}
