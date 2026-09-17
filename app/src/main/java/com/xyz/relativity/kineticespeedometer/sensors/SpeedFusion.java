package com.xyz.relativity.kineticespeedometer.sensors;

/**
 * Estimates forward speed between GPS fixes and gradually corrects inertial drift.
 */
public final class SpeedFusion {
	private static final float GPS_BASE_CORRECTION_GAIN = 0.25f;
	private static final float GPS_GAIN_PER_ERROR_MPS = 0.07f;
	private static final float MAX_GPS_CORRECTION_GAIN = 0.65f;
	private static final float BASE_GPS_CORRECTION_MPS = 0.40f;
	private static final float GPS_CORRECTION_PER_ERROR_MPS = 0.16f;
	private static final float MAX_GPS_CORRECTION_MPS = 2.0f;
	private static final float STOP_GPS_SPEED_MPS = 0.25f;
	private static final float STOP_ESTIMATED_SPEED_MPS = 1.0f;
	private static final float BIAS_LEARNING_GAIN = 0.15f;
	private static final float MAX_ACCELERATION_BIAS_MPS2 = 1.0f;

	private float speedMps;
	private float accelerationBiasMps2;
	private float rawAccelerationChangeMps;
	private float accelerationElapsedSeconds;
	private float lastGpsSpeedMps;
	private boolean initialized;

	public void reset(float gpsSpeedMps) {
		speedMps = Math.max(0.0f, gpsSpeedMps);
		accelerationBiasMps2 = 0.0f;
		rawAccelerationChangeMps = 0.0f;
		accelerationElapsedSeconds = 0.0f;
		lastGpsSpeedMps = speedMps;
		initialized = true;
	}

	public boolean isInitialized() {
		return initialized;
	}

	public void integrateAcceleration(float forwardAccelerationMps2, float elapsedSeconds) {
		if (!initialized || elapsedSeconds <= 0.0f) {
			return;
		}

		float correctedAccelerationMps2 = forwardAccelerationMps2 - accelerationBiasMps2;
		speedMps = Math.max(0.0f, speedMps + (correctedAccelerationMps2 * elapsedSeconds));
		rawAccelerationChangeMps += forwardAccelerationMps2 * elapsedSeconds;
		accelerationElapsedSeconds += elapsedSeconds;
	}

	public void correctWithGps(float gpsSpeedMps, float elapsedSeconds) {
		if (!initialized) {
			reset(gpsSpeedMps);
			return;
		}

		if (accelerationElapsedSeconds > 0.0f && elapsedSeconds > 0.0f) {
			float gpsAccelerationMps2 = (gpsSpeedMps - lastGpsSpeedMps) / elapsedSeconds;
			float rawAccelerationMps2 = rawAccelerationChangeMps / accelerationElapsedSeconds;
			float measuredBiasMps2 = rawAccelerationMps2 - gpsAccelerationMps2;
			accelerationBiasMps2 = clamp(
					accelerationBiasMps2 + (BIAS_LEARNING_GAIN * (measuredBiasMps2 - accelerationBiasMps2)),
					-MAX_ACCELERATION_BIAS_MPS2,
					MAX_ACCELERATION_BIAS_MPS2);
		}

		if (gpsSpeedMps <= STOP_GPS_SPEED_MPS && speedMps <= STOP_ESTIMATED_SPEED_MPS) {
			speedMps = 0.0f;
			lastGpsSpeedMps = 0.0f;
			rawAccelerationChangeMps = 0.0f;
			accelerationElapsedSeconds = 0.0f;
			return;
		}

		float speedErrorMps = gpsSpeedMps - speedMps;
		float absoluteSpeedErrorMps = Math.abs(speedErrorMps);
		float correctionGain = Math.min(MAX_GPS_CORRECTION_GAIN,
				GPS_BASE_CORRECTION_GAIN + (GPS_GAIN_PER_ERROR_MPS * absoluteSpeedErrorMps));
		float maximumCorrectionMps = Math.min(MAX_GPS_CORRECTION_MPS,
				BASE_GPS_CORRECTION_MPS + (GPS_CORRECTION_PER_ERROR_MPS * absoluteSpeedErrorMps));
		float correctionMps = clamp(
				speedErrorMps * correctionGain,
				-maximumCorrectionMps,
				maximumCorrectionMps);
		speedMps = Math.max(0.0f, speedMps + correctionMps);
		lastGpsSpeedMps = Math.max(0.0f, gpsSpeedMps);
		rawAccelerationChangeMps = 0.0f;
		accelerationElapsedSeconds = 0.0f;
	}

	public float getSpeedMps() {
		return speedMps;
	}

	float getAccelerationBiasMps2() {
		return accelerationBiasMps2;
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}
}
