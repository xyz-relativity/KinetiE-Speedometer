package com.xyz.relativity.kineticespeedometer.fusion;

/**
 * Decides which GPS fixes reach the estimator and how much to trust their speed. Shared by the
 * app and by test replays of recorded drives so both apply exactly the same rules.
 */
public final class GpsFixPolicy {
	/** Fixes with a worse horizontal accuracy than this are not GPS quality and are dropped. */
	public static final float MAX_USABLE_POSITION_ACCURACY_M = 100f;
	/** Below this position accuracy an unreported speed accuracy is assumed to be good. */
	public static final float GOOD_POSITION_ACCURACY_M = 25f;
	public static final float ASSUMED_SPEED_SIGMA_GOOD_FIX_MPS = 0.5f;
	public static final float ASSUMED_SPEED_SIGMA_POOR_FIX_MPS = 1.5f;
	public static final float MAX_PLAUSIBLE_SPEED_MPS = 150f;

	private GpsFixPolicy() {}

	/**
	 * @param speedMps           reported speed, NaN when the fix carries none
	 * @param positionAccuracyM  reported horizontal accuracy, NaN when unknown
	 */
	public static boolean isUsable(float speedMps, float positionAccuracyM) {
		if (Float.isNaN(speedMps) || speedMps < 0f || speedMps > MAX_PLAUSIBLE_SPEED_MPS) {
			return false;
		}
		return Float.isNaN(positionAccuracyM) || positionAccuracyM <= MAX_USABLE_POSITION_ACCURACY_M;
	}

	/**
	 * One-sigma speed uncertainty to hand to the estimator.
	 *
	 * @param speedAccuracyMps  reported speed accuracy, NaN or 0 when unknown
	 * @param positionAccuracyM reported horizontal accuracy, NaN when unknown
	 */
	public static float speedSigma(float speedAccuracyMps, float positionAccuracyM) {
		if (!Float.isNaN(speedAccuracyMps) && speedAccuracyMps > 0f) {
			return speedAccuracyMps;
		}
		boolean goodFix = !Float.isNaN(positionAccuracyM) && positionAccuracyM <= GOOD_POSITION_ACCURACY_M;
		return goodFix ? ASSUMED_SPEED_SIGMA_GOOD_FIX_MPS : ASSUMED_SPEED_SIGMA_POOR_FIX_MPS;
	}
}
