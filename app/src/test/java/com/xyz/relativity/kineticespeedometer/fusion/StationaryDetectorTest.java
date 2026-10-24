package com.xyz.relativity.kineticespeedometer.fusion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StationaryDetectorTest {
	private static final long STEP = 20_000_000L;

	@Test
	public void notReadyBeforeMinimumSamples() {
		StationaryDetector d = new StationaryDetector();
		for (int i = 0; i < StationaryDetector.MIN_SAMPLES - 1; i++) {
			d.add(i * STEP, 0.1f);
		}
		assertFalse(d.isReady());
		assertTrue(Float.isNaN(d.getStdDev()));
		d.add(StationaryDetector.MIN_SAMPLES * STEP, 0.1f);
		assertTrue(d.isReady());
	}

	@Test
	public void quietSignalHasSmallMeanAndSpread() {
		StationaryDetector d = new StationaryDetector();
		for (int i = 0; i < 50; i++) {
			d.add(i * STEP, 0.05f + (i % 2 == 0 ? 0.01f : -0.01f));
		}
		assertEquals(0.05f, d.getMean(), 1e-3f);
		assertEquals(0.01f, d.getStdDev(), 1e-3f);
	}

	@Test
	public void windowDropsSamplesOlderThanOneSecond() {
		StationaryDetector d = new StationaryDetector();
		long t = 0;
		for (int i = 0; i < 50; i++) {
			t += STEP;
			d.add(t, i % 2 == 0 ? 5f : 0f);
		}
		assertTrue(d.getStdDev() > 2f);
		for (int i = 0; i < 60; i++) {
			t += STEP;
			d.add(t, 0.1f);
		}
		assertEquals(0.1f, d.getMean(), 1e-4f);
		assertEquals(0f, d.getStdDev(), 1e-3f);
	}
}
