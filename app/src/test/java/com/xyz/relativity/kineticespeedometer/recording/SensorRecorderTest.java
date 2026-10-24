package com.xyz.relativity.kineticespeedometer.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.xyz.relativity.kineticespeedometer.fusion.RecordedDrive;
import com.xyz.relativity.kineticespeedometer.fusion.SpeedEstimator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.nio.file.Files;
import java.util.List;

public class SensorRecorderTest {
	private File directory;
	private SensorRecorder recorder;

	@Before
	public void setUp() throws Exception {
		directory = Files.createTempDirectory("sensor-recorder").toFile();
		recorder = new SensorRecorder(new File(directory, "recordings"));
	}

	@After
	public void tearDown() throws Exception {
		recorder.shutdown();
		recorder.awaitIdle(2000);
		deleteRecursively(directory);
	}

	@Test
	public void writesHeaderAndRowsInArrivalOrder() throws Exception {
		assertFalse(recorder.isRecording());
		assertNull(recorder.getFile());
		recorder.recordGravity(1L, new float[]{0f, 0f, 9.81f}); // ignored while not recording

		File file = recorder.start();
		assertTrue(recorder.isRecording());
		assertTrue(file.getName().startsWith("drive-") && file.getName().endsWith(".csv"));
		recorder.recordGravity(1_000L, new float[]{0f, 0f, 9.81f});
		recorder.recordLinearAcceleration(1_000L, new float[]{0.1f, 0.2f, 0.3f});
		recorder.recordGps(500L, 12.5f, Float.NaN, 5f);
		recorder.stop();
		assertFalse(recorder.isRecording());
		recorder.awaitIdle(5000);
		assertNull(recorder.getLastError());

		List<String> lines = Files.readAllLines(file.toPath());
		assertEquals(4, lines.size());
		assertEquals(SensorRecorder.HEADER, lines.get(0));
		assertEquals("grav,1000,0.0,0.0,9.81", lines.get(1));
		assertEquals("lin,1000,0.1,0.2,0.3", lines.get(2));
		assertEquals("gps,500,12.5,NaN,5.0", lines.get(3));
	}

	@Test
	public void recordingReplaysThroughTheEstimator() throws Exception {
		File file = recorder.start();
		float[] gravity = {0f, 0f, 9.81f};
		float[] linear = {0.02f, -0.01f, 0.03f};
		long t = 0;
		for (int i = 1; i <= 50 * 3; i++) {
			t += 20_000_000L;
			recorder.recordGravity(t, gravity);
			recorder.recordLinearAcceleration(t, linear);
			if (i % 50 == 0) {
				recorder.recordGps(t - 500_000_000L, 0.1f, 0.3f, 4f);
			}
		}
		recorder.stop();
		recorder.awaitIdle(5000);

		List<RecordedDrive.Point> points;
		try (Reader reader = new FileReader(file)) {
			points = RecordedDrive.replay(reader, new SpeedEstimator());
		}
		assertEquals(150, points.size());
		RecordedDrive.Point last = points.get(points.size() - 1);
		assertTrue("parked car is detected as stationary", last.stationary);
		assertEquals(0f, last.speedMps, 0f);
	}

	@Test
	public void stopAndFlushWithoutStartAreHarmless() throws Exception {
		recorder.flush();
		recorder.stop();
		recorder.awaitIdle(1000);
		assertNull(recorder.getFile());
		assertFalse(new File(directory, "recordings").exists());
	}

	@Test
	public void startWhileRecordingKeepsTheSameFile() throws Exception {
		File first = recorder.start();
		assertEquals(first, recorder.start());
	}

	private static void deleteRecursively(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		//noinspection ResultOfMethodCallIgnored
		file.delete();
	}
}
