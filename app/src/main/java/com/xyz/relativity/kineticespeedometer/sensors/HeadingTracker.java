package com.xyz.relativity.kineticespeedometer.sensors;

/**
 * Maintains a travel direction in world coordinates while the device rotates.
 */
public final class HeadingTracker {
	private boolean hasAttitudeHeading;
	private boolean hasTravelDirection;
	private float attitudeHeadingRadians;
	private float directionX;
	private float directionY;

	public void updateAttitudeHeading(float measuredHeadingRadians) {
		float normalizedHeading = normalizeAngle(measuredHeadingRadians);
		if (!hasAttitudeHeading) {
			attitudeHeadingRadians = normalizedHeading;
			hasAttitudeHeading = true;
			return;
		}

		rotateTravelDirection(normalizeAngle(normalizedHeading - attitudeHeadingRadians));
		attitudeHeadingRadians = normalizedHeading;
	}

	public void integrateGyroscopeYaw(float yawChangeRadians) {
		if (!hasAttitudeHeading) {
			return;
		}

		rotateTravelDirection(yawChangeRadians);
		attitudeHeadingRadians = normalizeAngle(attitudeHeadingRadians + yawChangeRadians);
	}

	public void setTravelDirection(float x, float y) {
		float length = (float) Math.hypot(x, y);
		if (length == 0.0f) {
			return;
		}
		directionX = x / length;
		directionY = y / length;
		hasTravelDirection = true;
	}

	public boolean hasAttitudeHeading() {
		return hasAttitudeHeading;
	}

	public boolean hasTravelDirection() {
		return hasTravelDirection;
	}

	public float getDirectionX() {
		return directionX;
	}

	public float getDirectionY() {
		return directionY;
	}

	private void rotateTravelDirection(float angleRadians) {
		if (!hasTravelDirection) {
			return;
		}

		float cosine = (float) Math.cos(angleRadians);
		float sine = (float) Math.sin(angleRadians);
		float rotatedX = (cosine * directionX) - (sine * directionY);
		directionY = (sine * directionX) + (cosine * directionY);
		directionX = rotatedX;
	}

	private static float normalizeAngle(float radians) {
		return (float) Math.atan2(Math.sin(radians), Math.cos(radians));
	}
}
