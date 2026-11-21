package info.openrocket.core.wyvern.flightlog;

import java.util.ArrayList;
import java.util.List;

/**
 * A recorded flight from an altimeter: altitude and, if the device logs it, vertical
 * velocity over time, plus the events the device marked.
 * <p>
 * Time is in seconds from liftoff, altitude in meters above the launch site, velocity
 * in m/s positive up. Samples are sorted by time.
 */
public class FlightLog {

	/** Something the altimeter marked during the flight. */
	public enum EventType {
		LIFTOFF, BURNOUT, APOGEE, DROGUE, MAIN
	}

	/**
	 * @param type the event
	 * @param time time from liftoff in s
	 */
	public record Event(EventType type, double time) {
	}

	private final String source;
	private final double[] time;
	private final double[] altitude;
	private final double[] velocity;
	private final List<Event> events;
	private final List<String> warnings;

	/**
	 * @param source   name of the device or format the log came from
	 * @param time     sample times in s from liftoff, ascending
	 * @param altitude altitude in m above the launch site, one per sample
	 * @param velocity vertical velocity in m/s, one per sample, or null if not logged
	 * @param events   events marked by the device
	 * @param warnings problems found while reading the file, in plain words
	 */
	public FlightLog(String source, double[] time, double[] altitude, double[] velocity, List<Event> events,
			List<String> warnings) {
		if (time.length != altitude.length || (velocity != null && velocity.length != time.length)) {
			throw new IllegalArgumentException("Flight log columns have different lengths");
		}
		if (time.length < 2) {
			throw new IllegalArgumentException("Flight log has fewer than two samples");
		}
		this.source = source;
		this.time = time;
		this.altitude = altitude;
		this.velocity = velocity;
		this.events = List.copyOf(events);
		this.warnings = List.copyOf(warnings);
	}

	public String getSource() {
		return source;
	}

	public int size() {
		return time.length;
	}

	public double getTime(int i) {
		return time[i];
	}

	public double getAltitude(int i) {
		return altitude[i];
	}

	public boolean hasVelocity() {
		return velocity != null;
	}

	/** Logged vertical velocity of a sample in m/s, or NaN if the device does not log velocity. */
	public double getVelocity(int i) {
		return velocity == null ? Double.NaN : velocity[i];
	}

	public List<Event> getEvents() {
		return events;
	}

	/** Time of the first event of a type in s, or NaN if the device did not mark it. */
	public double eventTime(EventType type) {
		for (Event e : events) {
			if (e.type() == type) {
				return e.time();
			}
		}
		return Double.NaN;
	}

	public List<String> getWarnings() {
		return warnings;
	}

	/** Linearly interpolated altitude at a time, clamped to the first and last sample. */
	public double altitudeAt(double t) {
		if (t <= time[0]) {
			return altitude[0];
		}
		int last = time.length - 1;
		if (t >= time[last]) {
			return altitude[last];
		}
		int lo = 0;
		int hi = last;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (time[mid] <= t) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		double span = time[hi] - time[lo];
		return span <= 0 ? altitude[hi] : altitude[lo] + (t - time[lo]) / span * (altitude[hi] - altitude[lo]);
	}

	/**
	 * Gaps in the recording: pairs of {start, end} times in s where consecutive samples
	 * are further apart than ten times the usual sample interval and at least 1 s.
	 */
	public List<double[]> gaps() {
		double[] steps = new double[time.length - 1];
		for (int i = 0; i < steps.length; i++) {
			steps[i] = time[i + 1] - time[i];
		}
		double[] sorted = steps.clone();
		java.util.Arrays.sort(sorted);
		double usual = sorted[sorted.length / 2];
		double limit = Math.max(1.0, 10 * usual);
		List<double[]> gaps = new ArrayList<>();
		for (int i = 0; i < steps.length; i++) {
			if (steps[i] > limit) {
				gaps.add(new double[] { time[i], time[i + 1] });
			}
		}
		return gaps;
	}
}
