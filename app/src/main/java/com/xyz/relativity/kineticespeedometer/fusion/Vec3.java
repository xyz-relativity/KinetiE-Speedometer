package com.xyz.relativity.kineticespeedometer.fusion;

/** Minimal float[3] helpers shared by the fusion classes. */
final class Vec3 {
	private Vec3() {}

	static float dot(float[] a, float[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	static float norm(float[] a) {
		return (float) Math.sqrt(dot(a, a));
	}

	static void copy(float[] src, float[] dst) {
		dst[0] = src[0];
		dst[1] = src[1];
		dst[2] = src[2];
	}

	static void zero(float[] a) {
		a[0] = 0f;
		a[1] = 0f;
		a[2] = 0f;
	}

	/** Writes src / |src| into dst; returns false and leaves dst untouched if src is (near) zero. */
	static boolean normalize(float[] src, float[] dst) {
		float n = norm(src);
		if (n < 1e-6f) {
			return false;
		}
		dst[0] = src[0] / n;
		dst[1] = src[1] / n;
		dst[2] = src[2] / n;
		return true;
	}
}
