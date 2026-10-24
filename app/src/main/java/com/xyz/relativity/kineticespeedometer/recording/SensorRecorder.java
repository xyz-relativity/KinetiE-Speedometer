package com.xyz.relativity.kineticespeedometer.recording;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Debug recorder that appends the raw inputs of the speed estimator to a CSV file, one file per
 * recording, so real drives can be replayed through the estimator offline.
 *
 * <p>Format: a header line {@value #HEADER}, then one row per event in arrival order. Row types:
 * {@code lin} (linear acceleration x, y, z), {@code grav} (gravity x, y, z) and {@code gps}
 * (speed m/s, speed accuracy m/s, position accuracy m; NaN where the fix had no value).
 * Timestamps are elapsed-realtime nanoseconds exactly as the estimator receives them. Rows are
 * written in arrival order on purpose: a fix arrives later than its own timestamp, and a replay
 * must see the same lag.
 *
 * <p>Record methods are meant for one thread (the main thread) and only append to a buffer; file
 * I/O happens on a single background thread, so callbacks are never blocked.
 */
public final class SensorRecorder {
	public static final String HEADER = "type,timestampNs,v0,v1,v2";
	public static final String TYPE_LINEAR_ACCELERATION = "lin";
	public static final String TYPE_GRAVITY = "grav";
	public static final String TYPE_GPS = "gps";
	private static final int FLUSH_THRESHOLD_CHARS = 16 * 1024;

	private final File directory;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final StringBuilder buffer = new StringBuilder(2 * FLUSH_THRESHOLD_CHARS);
	private File file;
	private boolean recording;
	private volatile IOException lastError;
	/** Touched only on the executor thread. */
	private Writer writer;

	public SensorRecorder(File directory) {
		this.directory = directory;
	}

	public boolean isRecording() {
		return recording;
	}

	/** File of the current or most recent recording, null before the first start. */
	public File getFile() {
		return file;
	}

	/** First I/O error since the last start, or null. */
	public IOException getLastError() {
		return lastError;
	}

	/** Starts a new recording file named by the current time and returns it; a no-op while recording. */
	public File start() {
		if (recording) {
			return file;
		}
		String name = "drive-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".csv";
		file = new File(directory, name);
		recording = true;
		lastError = null;
		final File target = file;
		executor.execute(new Runnable() {
			@Override
			public void run() {
				try {
					//noinspection ResultOfMethodCallIgnored
					directory.mkdirs();
					writer = new BufferedWriter(new FileWriter(target));
					writer.write(HEADER);
					writer.write('\n');
				} catch (IOException e) {
					lastError = e;
					writer = null;
				}
			}
		});
		return file;
	}

	/** Flushes what is buffered and closes the file; a no-op when not recording. */
	public void stop() {
		if (!recording) {
			return;
		}
		recording = false;
		flush();
		executor.execute(new Runnable() {
			@Override
			public void run() {
				if (writer == null) {
					return;
				}
				try {
					writer.close();
				} catch (IOException e) {
					lastError = e;
				}
				writer = null;
			}
		});
	}

	/** Hands the buffered rows to the writer thread. Call on pause so nothing is lost if the app dies. */
	public void flush() {
		if (buffer.length() == 0) {
			return;
		}
		final String chunk = buffer.toString();
		buffer.setLength(0);
		executor.execute(new Runnable() {
			@Override
			public void run() {
				if (writer == null) {
					return;
				}
				try {
					writer.write(chunk);
					writer.flush();
				} catch (IOException e) {
					lastError = e;
				}
			}
		});
	}

	/** Stops recording and releases the writer thread. The recorder cannot be used afterwards. */
	public void shutdown() {
		stop();
		executor.shutdown();
	}

	/** Blocks until queued writes are done, or the timeout passes. Meant for tests. */
	public void awaitIdle(long timeoutMs) throws InterruptedException {
		if (executor.isShutdown()) {
			executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
			return;
		}
		try {
			Future<?> marker = executor.submit(new Runnable() {
				@Override
				public void run() {
				}
			});
			marker.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException | ExecutionException | TimeoutException ignored) {
			// shut down concurrently, or still busy after the timeout: nothing more to wait for
		}
	}

	public void recordLinearAcceleration(long timestampNs, float[] xyz) {
		append(TYPE_LINEAR_ACCELERATION, timestampNs, xyz[0], xyz[1], xyz[2]);
	}

	public void recordGravity(long timestampNs, float[] xyz) {
		append(TYPE_GRAVITY, timestampNs, xyz[0], xyz[1], xyz[2]);
	}

	/** Pass NaN for any value the fix did not report. */
	public void recordGps(long timestampNs, float speedMps, float speedAccuracyMps, float positionAccuracyM) {
		append(TYPE_GPS, timestampNs, speedMps, speedAccuracyMps, positionAccuracyM);
	}

	private void append(String type, long timestampNs, float v0, float v1, float v2) {
		if (!recording) {
			return;
		}
		// StringBuilder.append(float) is locale independent and round-trips through Float.parseFloat.
		buffer.append(type).append(',').append(timestampNs)
				.append(',').append(v0).append(',').append(v1).append(',').append(v2).append('\n');
		if (buffer.length() >= FLUSH_THRESHOLD_CHARS) {
			flush();
		}
	}
}
