package info.openrocket.core.wyvern.flightlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import info.openrocket.core.wyvern.flightlog.FlightLog.EventType;
import info.openrocket.core.wyvern.flightlog.FlightLogReader.ColumnMapping;
import info.openrocket.core.wyvern.flightlog.FlightLogReader.Format;

/**
 * The fixtures are synthetic flights built from straight lines, so every expected value
 * follows from the numbers used to build them: climb at 100 m/s for 10 s to 1000 m,
 * descend under drogue at 25 m/s for 32 s to 200 m, then under main at 5 m/s for 40 s
 * to the ground at 82 s, then 5 s at rest.
 */
public class FlightLogTest {

	private static final double FOOT = 0.3048;

	/** Altitude in m of the synthetic flight. */
	private static double altitude(double t) {
		if (t <= 10) {
			return 100 * t;
		} else if (t <= 42) {
			return 1000 - 25 * (t - 10);
		} else if (t <= 82) {
			return 200 - 5 * (t - 42);
		}
		return 0;
	}

	private static double velocity(double t) {
		return t < 10 ? 100 : t < 42 ? -25 : t < 82 ? -5 : 0;
	}

	private static String fluctus(boolean events) {
		StringBuilder sb = new StringBuilder("\uFEFFTime,Altitude,Pressure,Velocity,Events\r\n");
		for (int i = 0; i <= 870; i++) {
			double t = i / 10.0;
			String event = !events ? "-" : i == 100 ? "Drogue" : i == 420 ? "Main" : "-";
			sb.append(String.format(Locale.ROOT, "%.2f,%.3f,1000.0,%.3f,%s\r\n", t, altitude(t) / FOOT,
					velocity(t) / FOOT, event));
		}
		return sb.toString();
	}

	private static String blueRavenRow(double t) {
		int ms = (int) Math.round(t * 1000);
		String clock = String.format(Locale.ROOT, "16:%02d:%02d.%03d", 33 + ms / 60000, (ms / 1000) % 60, ms % 1000);
		return String.format(Locale.ROOT, "2025,10,12,%s,%.3f,%.2f,%.2f,%d,%d,%d,%d\n", clock, t, altitude(t) / FOOT,
				velocity(t) / FOOT, 1, t >= 10 ? 1 : 0, t >= 10.5 ? 1 : 0, t >= 42 ? 1 : 0);
	}

	private static final String BLUE_RAVEN_HEADER = "Year,Month,Day,Time,Flight_Time_(s),Baro_Altitude_AGL_(feet),"
			+ "Velocity_Up,Liftoff,Apogee,Apo_fired,Main_fired\n";

	@Test
	public void fluctusFileIsReadInFeet() throws IOException {
		FlightLog log = FlightLogReader.read(new StringReader(fluctus(true)));
		assertEquals("Silicdyne Fluctus", log.getSource());
		assertEquals(871, log.size());
		assertEquals(1000, log.getAltitude(100), 0.01);
		assertEquals(100, log.getVelocity(50), 0.01);
		assertEquals(10.0, log.eventTime(EventType.DROGUE), 1e-9);
		assertEquals(42.0, log.eventTime(EventType.MAIN), 1e-9);
		assertTrue(log.getWarnings().isEmpty());
	}

	@Test
	public void statisticsFollowFromTheFixture() throws IOException {
		FlightLogStats stats = FlightLogStats.of(FlightLogReader.read(new StringReader(fluctus(true))));
		assertEquals(1000, stats.apogee(), 0.01);
		assertEquals(10.0, stats.apogeeTime(), 1e-9);
		assertEquals(100, stats.maxVelocity(), 0.01);
		assertEquals(42.0, stats.mainTime(), 1e-9);
		assertEquals(200, stats.mainAltitude(), 0.01);
		assertEquals(25, stats.drogueRate(), 0.01);
		assertEquals(5, stats.mainRate(), 0.01);
		// Within 3 m of the ground at 5 m/s is reached 0.6 s before touchdown
		assertEquals(82.0, stats.landingTime(), 0.7);
		assertEquals(72.0, stats.descentTime(), 0.7);
	}

	@Test
	public void mainDeploymentIsFoundWithoutEvents() throws IOException {
		FlightLogStats stats = FlightLogStats.of(FlightLogReader.read(new StringReader(fluctus(false))));
		assertEquals(42.0, stats.mainTime(), 0.2);
		assertEquals(25, stats.drogueRate(), 0.05);
		assertEquals(5, stats.mainRate(), 0.05);
	}

	@Test
	public void blueRavenRepeatedRowsAreDroppedAndGapsReported() throws IOException {
		StringBuilder sb = new StringBuilder(BLUE_RAVEN_HEADER);
		// 0 to 30 s, then the first 5 s again, then nothing until 85 s
		for (int i = 0; i <= 600; i++) {
			sb.append(blueRavenRow(i / 20.0));
		}
		for (int i = 0; i <= 100; i++) {
			sb.append(blueRavenRow(i / 20.0));
		}
		for (int i = 1700; i <= 1740; i++) {
			sb.append(blueRavenRow(i / 20.0));
		}
		FlightLog log = FlightLogReader.read(new StringReader(sb.toString()));
		assertEquals("Featherweight Blue Raven", log.getSource());
		assertEquals(601 + 41, log.size());
		assertEquals(0.0, log.eventTime(EventType.LIFTOFF), 1e-9);
		assertEquals(10.0, log.eventTime(EventType.APOGEE), 1e-9);
		assertEquals(10.5, log.eventTime(EventType.DROGUE), 1e-9);
		assertEquals(1, log.gaps().size());
		assertEquals(30.0, log.gaps().get(0)[0], 1e-9);
		assertEquals(85.0, log.gaps().get(0)[1], 1e-9);
		assertEquals(2, log.getWarnings().size());

		FlightLogStats stats = FlightLogStats.of(log);
		assertEquals(1000, stats.apogee(), 0.01);
		assertEquals(25, stats.drogueRate(), 0.01);
		// The main opened and the rocket landed where there is no data
		assertTrue(Double.isNaN(stats.mainRate()));
		assertTrue(Double.isNaN(stats.landingTime()));
	}

	@Test
	public void genericCsvUsesTheColumnMapping() throws IOException {
		String header = "Sample,Time (s),Height (ft),Speed\n";
		ColumnMapping guess = FlightLogReader.guessMapping(header);
		assertNotNull(guess);
		assertEquals(1, guess.timeColumn());
		assertEquals(2, guess.altitudeColumn());
		assertEquals(FOOT, guess.altitudeScale(), 1e-12);
		assertEquals(-1, guess.velocityColumn());
		assertEquals(Format.GENERIC, FlightLogReader.detect(header));

		StringBuilder sb = new StringBuilder(header);
		for (int i = 0; i <= 870; i++) {
			double t = i / 10.0;
			sb.append(String.format(Locale.ROOT, "%d,%.2f,%.3f,0\n", i, t, altitude(t) / FOOT));
		}
		FlightLog log = FlightLogReader.read(new StringReader(sb.toString()));
		assertFalse(log.hasVelocity());
		FlightLogStats stats = FlightLogStats.of(log);
		assertEquals(1000, stats.apogee(), 0.01);
		// Derived from the altitude: 100 m/s on the way up
		assertEquals(100, stats.maxVelocity(), 0.01);
		assertEquals(5, stats.mainRate(), 0.05);
	}

	@Test
	public void interpolationAndBadFiles() throws IOException {
		FlightLog log = FlightLogReader.read(new StringReader(fluctus(true)));
		assertEquals(505, log.altitudeAt(5.05), 0.01);
		assertEquals(0, log.altitudeAt(-1), 1e-9);
		assertNull(FlightLogReader.guessMapping("a,b,c\n"));
		assertThrows(IllegalArgumentException.class, () -> FlightLogReader.read(new StringReader("")));
		assertThrows(IllegalArgumentException.class, () -> FlightLogReader.read(new StringReader("a,b,c\n1,2,3\n")));
		assertThrows(IllegalArgumentException.class,
				() -> FlightLogReader.read(new StringReader("Time,Altitude\n1,2\n")));
	}

	@Test
	public void dataEndingAtGroundHitCountsAsLanded() {
		// Like a simulation: samples stop at touchdown, with no time at rest
		int n = 821;
		double[] time = new double[n];
		double[] alt = new double[n];
		for (int i = 0; i < n; i++) {
			time[i] = i / 10.0;
			alt[i] = altitude(time[i]);
		}
		FlightLogStats stats = FlightLogStats.of(new FlightLog("test", time, alt, null, java.util.List.of(),
				java.util.List.of()));
		assertEquals(82.0, stats.landingTime(), 1e-9);
		assertEquals(72.0, stats.descentTime(), 1e-9);

		// Cut off at 500 m under drogue: still in the air
		int cut = 301;
		FlightLogStats airborne = FlightLogStats.of(new FlightLog("test", java.util.Arrays.copyOf(time, cut),
				java.util.Arrays.copyOf(alt, cut), null, java.util.List.of(), java.util.List.of()));
		assertTrue(Double.isNaN(airborne.landingTime()));
		assertEquals(25, airborne.drogueRate(), 0.01);
	}
}
