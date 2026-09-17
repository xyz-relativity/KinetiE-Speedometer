package com.xyz.relativity.kineticespeedometer.sensors;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpeedFusionTest {
	@Test
	public void largeGpsCorrectionIsExpandedButCapped() {
		SpeedFusion fusion = new SpeedFusion();
		fusion.reset(10.0f);

		fusion.correctWithGps(20.0f, 0.25f);

		assertEquals(12.0f, fusion.getSpeedMps(), 0.0001f);
	}

	@Test
	public void smallGpsCorrectionRemainsSmooth() {
		SpeedFusion fusion = new SpeedFusion();
		fusion.reset(10.0f);

		fusion.correctWithGps(10.5f, 0.25f);

		assertEquals(10.1425f, fusion.getSpeedMps(), 0.0001f);
	}

	@Test
	public void signedAccelerationCanIncreaseAndDecreaseSpeed() {
		SpeedFusion fusion = new SpeedFusion();
		fusion.reset(10.0f);

		fusion.integrateAcceleration(2.0f, 0.5f);
		fusion.integrateAcceleration(-2.0f, 0.5f);

		assertEquals(10.0f, fusion.getSpeedMps(), 0.0001f);
	}

	@Test
	public void gpsFixLearnsPositiveAccelerationBias() {
		SpeedFusion fusion = new SpeedFusion();
		fusion.reset(10.0f);
		fusion.integrateAcceleration(1.0f, 1.0f);

		fusion.correctWithGps(10.0f, 1.0f);

		assertTrue(fusion.getAccelerationBiasMps2() > 0.0f);
	}

	@Test
	public void lowSpeedGpsFixSnapsNearStationaryEstimateToZero() {
		SpeedFusion fusion = new SpeedFusion();
		fusion.reset(0.8f);

		fusion.correctWithGps(0.0f, 0.25f);

		assertEquals(0.0f, fusion.getSpeedMps(), 0.0f);
	}

	@Test
	public void lowSpeedGpsFixDoesNotSnapMovingEstimateToZero() {
		SpeedFusion fusion = new SpeedFusion();
		fusion.reset(1.2f);

		fusion.correctWithGps(0.0f, 0.25f);

		assertEquals(0.9f, fusion.getSpeedMps(), 0.0001f);
	}
}
