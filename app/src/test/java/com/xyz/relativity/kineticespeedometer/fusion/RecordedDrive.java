package com.xyz.relativity.kineticespeedometer.fusion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays a drive recorded by the app's SensorRecorder through a {@link SpeedEstimator}, applying
 * the same gating as the app ({@link GpsFixPolicy}). Rows are replayed in file order, which is the
 * order the app received them; sorting by timestamp would hide the GPS latency the filter must cope
 * with. One {@link Point} is produced per linear-acceleration row once gravity is known.
 *
 * <p>To turn a real drive into a regression test, copy its CSV under
 * {@code app/src/test/resources/} and assert on the returned points.
 */
public final class RecordedDrive {
	public static final class Point {
		public final long timestampNs;
		public final float speedMps;
		public final boolean stationary;
		public final boolean axisKnown;

		Point(long timestampNs, float speedMps, boolean stationary, boolean axisKnown) {
			this.timestampNs = timestampNs;
			this.speedMps = speedMps;
			this.stationary = stationary;
			this.axisKnown = axisKnown;
		}
	}

	private RecordedDrive() {}

	/** Replays a classpath resource such as {@code /fusion/synthetic-drive.csv}. */
	public static List<Point> replayResource(String resource, SpeedEstimator estimator) throws IOException {
		InputStream in = RecordedDrive.class.getResourceAsStream(resource);
		if (in == null) {
			throw new IOException("resource not found: " + resource);
		}
		try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			return replay(reader, estimator);
		}
	}

	public static List<Point> replay(Reader reader, SpeedEstimator estimator) throws IOException {
		BufferedReader lines = new BufferedReader(reader);
		float[] gravity = new float[3];
		float[] linear = new float[3];
		boolean hasGravity = false;
		List<Point> points = new ArrayList<>();
		String line;
		int lineNumber = 0;
		while ((line = lines.readLine()) != null) {
			lineNumber++;
			line = line.trim();
			if (line.isEmpty() || line.startsWith("#") || line.startsWith("type,")) {
				continue;
			}
			String[] fields = line.split(",", -1);
			if (fields.length != 5) {
				throw new IOException("line " + lineNumber + ": expected 5 columns, got " + fields.length);
			}
			long timestampNs;
			try {
				timestampNs = Long.parseLong(fields[1]);
				switch (fields[0]) {
					case "grav":
						parseVector(fields, gravity);
						hasGravity = true;
						break;
					case "lin":
						parseVector(fields, linear);
						if (hasGravity) {
							estimator.onSensorSample(timestampNs, linear, gravity);
							points.add(new Point(timestampNs, estimator.getSpeedMps(),
									estimator.isStationary(), estimator.isForwardAxisKnown()));
						}
						break;
					case "gps":
						float speed = Float.parseFloat(fields[2]);
						float speedAccuracy = Float.parseFloat(fields[3]);
						float positionAccuracy = Float.parseFloat(fields[4]);
						if (GpsFixPolicy.isUsable(speed, positionAccuracy)) {
							estimator.onGpsSpeed(timestampNs, speed, GpsFixPolicy.speedSigma(speedAccuracy, positionAccuracy));
						}
						break;
					default:
						throw new IOException("line " + lineNumber + ": unknown row type " + fields[0]);
				}
			} catch (NumberFormatException e) {
				throw new IOException("line " + lineNumber + ": " + e.getMessage(), e);
			}
		}
		return points;
	}

	private static void parseVector(String[] fields, float[] dst) {
		dst[0] = Float.parseFloat(fields[2]);
		dst[1] = Float.parseFloat(fields[3]);
		dst[2] = Float.parseFloat(fields[4]);
	}
}
