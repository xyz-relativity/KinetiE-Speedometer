package com.xyz.relativity.kineticespeedometer.fusion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GpsFixPolicyTest {
	@Test
	public void fixesWithoutSpeedOrWithAbsurdValuesAreUnusable() {
		assertFalse(GpsFixPolicy.isUsable(Float.NaN, 5f));
		assertFalse(GpsFixPolicy.isUsable(-1f, 5f));
		assertFalse(GpsFixPolicy.isUsable(200f, 5f));
		assertFalse(GpsFixPolicy.isUsable(10f, 150f));
		assertTrue(GpsFixPolicy.isUsable(10f, 50f));
		assertTrue(GpsFixPolicy.isUsable(0f, Float.NaN));
	}

	@Test
	public void reportedSpeedAccuracyWins() {
		assertEquals(0.7f, GpsFixPolicy.speedSigma(0.7f, 3f), 0f);
	}

	@Test
	public void missingSpeedAccuracyFallsBackOnPositionAccuracy() {
		assertEquals(GpsFixPolicy.ASSUMED_SPEED_SIGMA_GOOD_FIX_MPS, GpsFixPolicy.speedSigma(Float.NaN, 10f), 0f);
		assertEquals(GpsFixPolicy.ASSUMED_SPEED_SIGMA_GOOD_FIX_MPS, GpsFixPolicy.speedSigma(0f, 10f), 0f);
		assertEquals(GpsFixPolicy.ASSUMED_SPEED_SIGMA_POOR_FIX_MPS, GpsFixPolicy.speedSigma(Float.NaN, 60f), 0f);
		assertEquals(GpsFixPolicy.ASSUMED_SPEED_SIGMA_POOR_FIX_MPS, GpsFixPolicy.speedSigma(Float.NaN, Float.NaN), 0f);
	}
}
