package com.xyz.relativity.kineticespeedometer.sensors;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HeadingTrackerTest {
	@Test
	public void gyroscopeYawRotatesTravelDirection() {
		HeadingTracker tracker = new HeadingTracker();
		tracker.updateAttitudeHeading(0.0f);
		tracker.setTravelDirection(0.0f, 1.0f);

		tracker.integrateGyroscopeYaw((float) (Math.PI / 2.0));

		assertEquals(-1.0f, tracker.getDirectionX(), 0.0001f);
		assertEquals(0.0f, tracker.getDirectionY(), 0.0001f);
	}

	@Test
	public void matchingRotationVectorDoesNotDoubleRotateGyroscopeYaw() {
		HeadingTracker tracker = new HeadingTracker();
		tracker.updateAttitudeHeading(0.0f);
		tracker.setTravelDirection(0.0f, 1.0f);
		float quarterTurn = (float) (Math.PI / 2.0);

		tracker.integrateGyroscopeYaw(quarterTurn);
		tracker.updateAttitudeHeading(quarterTurn);

		assertEquals(-1.0f, tracker.getDirectionX(), 0.0001f);
		assertEquals(0.0f, tracker.getDirectionY(), 0.0001f);
	}
}
