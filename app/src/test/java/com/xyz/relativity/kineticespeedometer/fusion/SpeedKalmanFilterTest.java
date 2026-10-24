package com.xyz.relativity.kineticespeedometer.fusion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpeedKalmanFilterTest {
	private static final long SECOND = 1_000_000_000L;
	private static final long STEP = 20_000_000L; // 50 Hz
	private static final float DT = 0.02f;

	@Test
	public void firstFixInitialisesSpeed() {
		SpeedKalmanFilter f = new SpeedKalmanFilter();
		f.predict(0, 0f, 0f, true);
		assertTrue(f.updateSpeed(0, 12f, 0.25f));
		assertEquals(12f, f.getSpeed(), 1e-6f);
		assertTrue(f.isInitialized());
	}

	@Test
	public void convergesToConstantGpsSpeed() {
		SpeedKalmanFilter f = new SpeedKalmanFilter();
		f.setBiasEnabled(false);
		long t = 0;
		for (int i = 0; i < 50 * 20; i++) {
			t += STEP;
			f.predict(t, 0f, DT, false);
			if (i % 50 == 49) {
				f.updateSpeed(t, 10f, 0.25f);
			}
		}
		assertEquals(10f, f.getSpeed(), 0.05f);
	}

	@Test
	public void learnsConstantAccelerationBias() {
		// Cruising at 10 m/s while the sensor reports 0.3 m/s^2 of pure bias.
		SpeedKalmanFilter f = new SpeedKalmanFilter();
		f.updateSpeed(0, 10f, 0.25f);
		long t = 0;
		for (int i = 0; i < 50 * 90; i++) {
			t += STEP;
			f.predict(t, 0.3f, DT, true);
			if (i % 50 == 49) {
				f.updateSpeed(t, 10f, 0.25f);
			}
		}
		assertEquals(0.3f, f.getBias(), 0.05f);
		assertEquals(10f, f.getSpeed(), 0.3f);
	}

	@Test
	public void delayedFixIsMatchedAgainstThePastPrediction() {
		// True speed v(t) = 2 t. Every fix refers to 0.8 s before delivery. With exact acceleration
		// input the innovations must be near zero and the estimate must track the present speed;
		// treating the fix as current would settle 1.6 m/s low.
		SpeedKalmanFilter f = new SpeedKalmanFilter();
		f.setBiasEnabled(false);
		f.updateSpeed(0, 0f, 0.09f);
		long t = 0;
		float maxError = 0f;
		for (int i = 1; i <= 50 * 20; i++) {
			t += STEP;
			f.predict(t, 2f, DT, true);
			if (i % 50 == 0 && t >= SECOND) {
				long tMeas = t - 800_000_000L;
				f.updateSpeed(tMeas, 2f * tMeas / 1e9f, 0.09f);
			}
			if (i > 50 * 5) {
				float truth = 2f * t / 1e9f;
				maxError = Math.max(maxError, Math.abs(f.getSpeed() - truth));
			}
		}
		assertTrue("max error " + maxError, maxError < 0.3f);
	}

	@Test
	public void isolatedOutlierIsRejectedButPersistentJumpIsAccepted() {
		SpeedKalmanFilter f = new SpeedKalmanFilter();
		f.setBiasEnabled(false);
		long t = 0;
		for (int i = 0; i < 50 * 10; i++) {
			t += STEP;
			f.predict(t, 0f, DT, true);
			if (i % 50 == 49) {
				f.updateSpeed(t, 10f, 0.09f);
			}
		}
		assertFalse(f.updateSpeed(t, 40f, 0.09f));
		assertEquals(10f, f.getSpeed(), 0.2f);
		assertFalse(f.updateSpeed(t, 40f, 0.09f));
		assertTrue(f.updateSpeed(t, 40f, 0.09f));
		assertTrue(f.getSpeed() > 15f);
	}

	@Test
	public void zeroVelocityUpdatePinsSpeedToZero() {
		SpeedKalmanFilter f = new SpeedKalmanFilter();
		f.updateSpeed(0, 5f, 0.25f);
		f.predict(STEP, 0f, DT, true);
		f.zeroVelocityUpdate();
		assertEquals(0f, f.getSpeed(), 0f);
	}

	@Test
	public void stationaryBiasObservationsConverge() {
		SpeedKalmanFilter f = new SpeedKalmanFilter();
		f.updateSpeed(0, 0f, 0.25f);
		long t = 0;
		for (int i = 0; i < 150; i++) {
			t += STEP;
			f.predict(t, 0.25f, DT, true);
			f.observeBias(0.25f);
			f.zeroVelocityUpdate();
		}
		assertEquals(0.25f, f.getBias(), 0.03f);
		assertEquals(0f, f.getSpeed(), 0f);
	}

	@Test
	public void disablingBiasHoldsItAtZero() {
		SpeedKalmanFilter f = new SpeedKalmanFilter();
		f.setBiasEnabled(false);
		f.updateSpeed(0, 10f, 0.25f);
		long t = 0;
		for (int i = 0; i < 50 * 30; i++) {
			t += STEP;
			f.predict(t, 0.3f, DT, false);
			if (i % 50 == 49) {
				f.updateSpeed(t, 10f, 0.25f);
			}
		}
		assertEquals(0f, f.getBias(), 0f);
		assertEquals(0f, f.getBiasVariance(), 0f);
	}
}
