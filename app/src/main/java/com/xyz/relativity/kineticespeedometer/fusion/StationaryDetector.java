package com.xyz.relativity.kineticespeedometer.fusion;

/**
 * Sliding one-second statistics of the linear-acceleration magnitude, used to decide whether the
 * vehicle is standing still. A parked car, even idling, shows a small mean and a small standard
 * deviation; a moving one picks up road and powertrain vibration well above that.
 */
public final class StationaryDetector {
	static final long WINDOW_NS = 1_000_000_000L;
	static final int MIN_SAMPLES = 10;
	private static final int CAPACITY = 1024;

	private final long[] times = new long[CAPACITY];
	private final float[] values = new float[CAPACITY];
	private int head;
	private int size;
	private double sum;
	private double sumOfSquares;
	private float mean = Float.NaN;
	private float stdDev = Float.NaN;

	public void add(long tNs, float magnitude) {
		while (size > 0) {
			int oldest = index(0);
			if (size < CAPACITY && tNs - times[oldest] <= WINDOW_NS) {
				break;
			}
			sum -= values[oldest];
			sumOfSquares -= values[oldest] * values[oldest];
			size--;
		}
		if (size == 0) {
			sum = 0;
			sumOfSquares = 0;
		}
		times[head] = tNs;
		values[head] = magnitude;
		head = (head + 1) % CAPACITY;
		size++;
		sum += magnitude;
		sumOfSquares += magnitude * magnitude;

		if (size >= MIN_SAMPLES) {
			double m = sum / size;
			double variance = Math.max(0.0, sumOfSquares / size - m * m);
			mean = (float) m;
			stdDev = (float) Math.sqrt(variance);
		} else {
			mean = Float.NaN;
			stdDev = Float.NaN;
		}
	}

	public void clear() {
		head = 0;
		size = 0;
		sum = 0;
		sumOfSquares = 0;
		mean = Float.NaN;
		stdDev = Float.NaN;
	}

	public boolean isReady() {
		return size >= MIN_SAMPLES;
	}

	/** Mean magnitude over the window, NaN until enough samples arrived. */
	public float getMean() {
		return mean;
	}

	/** Standard deviation of the magnitude over the window, NaN until enough samples arrived. */
	public float getStdDev() {
		return stdDev;
	}

	private int index(int i) {
		return (head - size + i + CAPACITY) % CAPACITY;
	}
}
