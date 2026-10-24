package com.xyz.relativity.kineticespeedometer.fusion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

public class RecordedDriveTest {
	private static final String RESOURCE = "/fusion/synthetic-drive.csv";
	private static final int RATE = 20;

	@Test
	public void replaysTheSyntheticDrive() throws IOException {
		List<RecordedDrive.Point> points = RecordedDrive.replayResource(RESOURCE, new SpeedEstimator());
		assertEquals(24 * RATE, points.size());

		RecordedDrive.Point parked = points.get((int) (3.5f * RATE) - 1);
		assertTrue("stationary while parked", parked.stationary);
		assertEquals(0f, parked.speedMps, 0f);

		RecordedDrive.Point last = points.get(points.size() - 1);
		assertEquals(24_000_000_000L, last.timestampNs);
		assertTrue("axis learned by the end", last.axisKnown);
		assertEquals("cruising at 15 m/s", 15f, last.speedMps, 1f);
	}

	@Test
	public void skipsHeaderCommentsAndBlankLines() throws IOException {
		String csv = "type,timestampNs,v0,v1,v2\n"
				+ "# comment\n"
				+ "\n"
				+ "grav,1000000000,0,0,9.81\n"
				+ "lin,1000000000,0,0,0\n"
				+ "gps,500000000,3.0,NaN,NaN\n"
				+ "lin,1020000000,0,0,0\n";
		List<RecordedDrive.Point> points = RecordedDrive.replay(new StringReader(csv), new SpeedEstimator());
		assertEquals(2, points.size());
		assertEquals(3f, points.get(1).speedMps, 1e-6f);
	}

	@Test
	public void linearRowsBeforeGravityAreIgnored() throws IOException {
		String csv = "lin,1000000000,0,0,0\n"
				+ "grav,1000000000,0,0,9.81\n"
				+ "lin,1020000000,0,0,0\n";
		assertEquals(1, RecordedDrive.replay(new StringReader(csv), new SpeedEstimator()).size());
	}

	@Test
	public void unusableFixesAreDropped() throws IOException {
		String csv = "grav,1000000000,0,0,9.81\n"
				+ "gps,900000000,3.0,NaN,500\n"
				+ "lin,1000000000,0,0,0\n";
		List<RecordedDrive.Point> points = RecordedDrive.replay(new StringReader(csv), new SpeedEstimator());
		assertEquals(0f, points.get(0).speedMps, 0f);
	}

	@Test
	public void malformedRowsAreReported() {
		expectFailure("grav,1,0,0\n", "line 1");
		expectFailure("bogus,1,0,0,0\n", "unknown row type");
		expectFailure("lin,abc,0,0,0\n", "line 1");
	}

	private static void expectFailure(String csv, String messagePart) {
		try {
			RecordedDrive.replay(new StringReader(csv), new SpeedEstimator());
			fail("expected an IOException for " + csv);
		} catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
		}
	}
}
