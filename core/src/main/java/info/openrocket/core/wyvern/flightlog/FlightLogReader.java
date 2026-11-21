package info.openrocket.core.wyvern.flightlog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import info.openrocket.core.wyvern.flightlog.FlightLog.Event;
import info.openrocket.core.wyvern.flightlog.FlightLog.EventType;

/**
 * Reads altimeter log files into {@link FlightLog}s.
 * <p>
 * Supported formats:
 * <ul>
 * <li>Featherweight Blue Raven low-rate CSV export. Altitude is the barometric altitude
 * above ground in feet, velocity is {@code Velocity_Up} in ft/s. Rows are placed by
 * their wall-clock time, because exports have been seen that repeat blocks of rows
 * while the flight time column keeps counting; repeated rows are dropped.</li>
 * <li>Silicdyne Fluctus CSV export ({@code Time,Altitude,Pressure,Velocity,Events}),
 * taken to be in feet and ft/s.</li>
 * <li>Any other CSV with a header row, through a {@link ColumnMapping}.</li>
 * </ul>
 */
public final class FlightLogReader {

	private static final double FOOT = 0.3048;

	/** The formats that can be recognized from the header row. */
	public enum Format {
		BLUE_RAVEN, FLUCTUS, GENERIC
	}

	/**
	 * Which columns of a generic CSV hold the flight data.
	 *
	 * @param timeColumn     index of the time column, in seconds
	 * @param altitudeColumn index of the altitude column
	 * @param altitudeScale  factor from the file's altitude unit to m (0.3048 for feet)
	 * @param velocityColumn index of the vertical velocity column, or -1 if there is none
	 * @param velocityScale  factor from the file's velocity unit to m/s
	 */
	public record ColumnMapping(int timeColumn, int altitudeColumn, double altitudeScale, int velocityColumn,
			double velocityScale) {
	}

	private FlightLogReader() {
	}

	/** Split a header or data row on commas, trimming cells and a leading byte order mark. */
	public static List<String> cells(String line) {
		String clean = line.startsWith("\uFEFF") ? line.substring(1) : line;
		List<String> cells = new ArrayList<>();
		for (String cell : clean.split(",", -1)) {
			String c = cell.trim();
			if (c.length() >= 2 && c.startsWith("\"") && c.endsWith("\"")) {
				c = c.substring(1, c.length() - 1);
			}
			cells.add(c);
		}
		return cells;
	}

	/** Recognize the format from the header row. */
	public static Format detect(String headerLine) {
		List<String> header = cells(headerLine);
		if (header.contains("Flight_Time_(s)") && header.contains("Baro_Altitude_AGL_(feet)")) {
			return Format.BLUE_RAVEN;
		}
		if (header.size() >= 5 && header.get(0).equalsIgnoreCase("Time") && header.get(1).equalsIgnoreCase("Altitude")
				&& header.get(2).equalsIgnoreCase("Pressure") && header.get(3).equalsIgnoreCase("Velocity")
				&& header.get(4).equalsIgnoreCase("Events")) {
			return Format.FLUCTUS;
		}
		return Format.GENERIC;
	}

	/**
	 * Guess the columns of a generic CSV from its header: a column whose name contains
	 * "time", one containing "alt" (or "height"), and optionally one containing "vel".
	 * Units are taken from "ft" or "feet" in the column name, otherwise meters.
	 *
	 * @return the guess, or null if no time or altitude column was found
	 */
	public static ColumnMapping guessMapping(String headerLine) {
		List<String> header = cells(headerLine);
		int time = -1;
		int altitude = -1;
		int velocity = -1;
		for (int i = 0; i < header.size(); i++) {
			String name = header.get(i).toLowerCase(Locale.ROOT);
			if (time < 0 && name.contains("time")) {
				time = i;
			} else if (altitude < 0 && (name.contains("alt") || name.contains("height"))) {
				altitude = i;
			} else if (velocity < 0 && name.contains("vel")) {
				velocity = i;
			}
		}
		if (time < 0 || altitude < 0) {
			return null;
		}
		return new ColumnMapping(time, altitude, isFeet(header.get(altitude)) ? FOOT : 1.0, velocity,
				velocity >= 0 && isFeet(header.get(velocity)) ? FOOT : 1.0);
	}

	private static boolean isFeet(String columnName) {
		String name = columnName.toLowerCase(Locale.ROOT);
		return name.contains("ft") || name.contains("feet");
	}

	/**
	 * Read a log, recognizing Blue Raven and Fluctus files and guessing the columns of
	 * anything else.
	 *
	 * @throws IOException              if the file cannot be read
	 * @throws IllegalArgumentException if the file holds no usable flight data
	 */
	public static FlightLog read(Reader reader) throws IOException {
		return read(reader, null);
	}

	/**
	 * Read a log. The mapping is only used for files that are not recognized as a known
	 * device format; pass null to guess it from the header.
	 */
	public static FlightLog read(Reader reader, ColumnMapping mapping) throws IOException {
		BufferedReader in = new BufferedReader(reader);
		String headerLine = in.readLine();
		while (headerLine != null && headerLine.isBlank()) {
			headerLine = in.readLine();
		}
		if (headerLine == null) {
			throw new IllegalArgumentException("The file is empty");
		}
		List<List<String>> rows = new ArrayList<>();
		for (String line = in.readLine(); line != null; line = in.readLine()) {
			if (!line.isBlank()) {
				rows.add(cells(line));
			}
		}
		switch (detect(headerLine)) {
		case BLUE_RAVEN:
			return readBlueRaven(cells(headerLine), rows);
		case FLUCTUS:
			return readFluctus(rows);
		default: {
			ColumnMapping use = mapping != null ? mapping : guessMapping(headerLine);
			if (use == null) {
				throw new IllegalArgumentException("No time and altitude columns were found in the file");
			}
			return readGeneric(rows, use);
		}
		}
	}

	////////  Blue Raven  ////////

	private static FlightLog readBlueRaven(List<String> header, List<List<String>> rows) {
		Map<String, Integer> col = new HashMap<>();
		for (int i = 0; i < header.size(); i++) {
			col.putIfAbsent(header.get(i), i);
		}
		for (String needed : new String[] { "Year", "Month", "Day", "Time", "Flight_Time_(s)",
				"Baro_Altitude_AGL_(feet)" }) {
			if (!col.containsKey(needed)) {
				throw new IllegalArgumentException("Blue Raven file is missing the column " + needed);
			}
		}
		Integer velocityColumn = col.get("Velocity_Up");
		Map<EventType, Integer> flags = new HashMap<>();
		putFlag(flags, col, EventType.LIFTOFF, "Liftoff");
		putFlag(flags, col, EventType.BURNOUT, "Burnout_Coast");
		putFlag(flags, col, EventType.APOGEE, "Apogee");
		putFlag(flags, col, EventType.DROGUE, "Apo_fired");
		putFlag(flags, col, EventType.MAIN, "Main_fired");

		// Key every row by its wall-clock time in seconds; the first row seen for a time wins
		TreeMap<Double, List<String>> byClock = new TreeMap<>();
		int unreadable = 0;
		for (List<String> row : rows) {
			try {
				LocalDate date = LocalDate.of(Integer.parseInt(row.get(col.get("Year"))),
						Integer.parseInt(row.get(col.get("Month"))), Integer.parseInt(row.get(col.get("Day"))));
				LocalTime clock = LocalTime.parse(row.get(col.get("Time")));
				double seconds = date.toEpochDay() * 86400.0 + clock.toNanoOfDay() / 1e9;
				byClock.putIfAbsent(seconds, row);
			} catch (NumberFormatException | DateTimeParseException | IndexOutOfBoundsException e) {
				unreadable++;
			}
		}
		if (byClock.size() < 2) {
			throw new IllegalArgumentException("Blue Raven file holds no readable samples");
		}

		List<String> warnings = new ArrayList<>();
		int repeated = rows.size() - unreadable - byClock.size();
		if (repeated > 0) {
			warnings.add(String.format(Locale.ROOT,
					"The file repeats %d of its %d rows; the repeats were dropped and samples placed by clock time.",
					repeated, rows.size()));
		}
		if (unreadable > 0) {
			warnings.add(unreadable + " rows could not be read and were skipped.");
		}

		// Flight time of the first row anchors the clock to "seconds from liftoff"
		double firstClock = byClock.firstKey();
		double firstFlightTime = parse(byClock.firstEntry().getValue(), col.get("Flight_Time_(s)"));
		if (Double.isNaN(firstFlightTime)) {
			firstFlightTime = 0;
		}
		int n = byClock.size();
		double[] time = new double[n];
		double[] altitude = new double[n];
		double[] velocity = velocityColumn == null ? null : new double[n];
		List<Event> events = new ArrayList<>();
		Map<EventType, Boolean> seen = new HashMap<>();
		int i = 0;
		for (Map.Entry<Double, List<String>> entry : byClock.entrySet()) {
			List<String> row = entry.getValue();
			// Clock stamps are whole milliseconds; rounding removes the noise of subtracting large numbers
			time[i] = Math.round((entry.getKey() - firstClock + firstFlightTime) * 1e4) / 1e4;
			altitude[i] = parse(row, col.get("Baro_Altitude_AGL_(feet)")) * FOOT;
			if (velocity != null) {
				velocity[i] = parse(row, velocityColumn) * FOOT;
			}
			for (Map.Entry<EventType, Integer> flag : flags.entrySet()) {
				if (!seen.containsKey(flag.getKey()) && parse(row, flag.getValue()) == 1) {
					seen.put(flag.getKey(), true);
					events.add(new Event(flag.getKey(), time[i]));
				}
			}
			i++;
		}
		events.sort(Comparator.comparingDouble(Event::time));
		FlightLog log = new FlightLog("Featherweight Blue Raven", time, altitude, velocity, events, warnings);
		return withGapWarnings(log);
	}

	private static void putFlag(Map<EventType, Integer> flags, Map<String, Integer> col, EventType type, String name) {
		if (col.containsKey(name)) {
			flags.put(type, col.get(name));
		}
	}

	////////  Fluctus  ////////

	private static FlightLog readFluctus(List<List<String>> rows) {
		List<double[]> samples = new ArrayList<>();
		List<Event> events = new ArrayList<>();
		for (List<String> row : rows) {
			double t = parse(row, 0);
			double alt = parse(row, 1);
			if (Double.isNaN(t) || Double.isNaN(alt)) {
				continue;
			}
			samples.add(new double[] { t, alt * FOOT, parse(row, 3) * FOOT });
			if (row.size() > 4) {
				String event = row.get(4).toLowerCase(Locale.ROOT);
				if (event.contains("drogue")) {
					events.add(new Event(EventType.DROGUE, t));
				} else if (event.contains("main")) {
					events.add(new Event(EventType.MAIN, t));
				} else if (event.contains("apogee")) {
					events.add(new Event(EventType.APOGEE, t));
				}
			}
		}
		return withGapWarnings(build("Silicdyne Fluctus", samples, true, events, new ArrayList<>()));
	}

	////////  Generic  ////////

	private static FlightLog readGeneric(List<List<String>> rows, ColumnMapping mapping) {
		List<double[]> samples = new ArrayList<>();
		for (List<String> row : rows) {
			double t = parse(row, mapping.timeColumn());
			double alt = parse(row, mapping.altitudeColumn());
			if (Double.isNaN(t) || Double.isNaN(alt)) {
				continue;
			}
			double v = mapping.velocityColumn() >= 0 ? parse(row, mapping.velocityColumn()) * mapping.velocityScale()
					: Double.NaN;
			samples.add(new double[] { t, alt * mapping.altitudeScale(), v });
		}
		return withGapWarnings(build("CSV", samples, mapping.velocityColumn() >= 0, new ArrayList<>(), new ArrayList<>()));
	}

	////////  Shared  ////////

	private static FlightLog build(String source, List<double[]> samples, boolean hasVelocity, List<Event> events,
			List<String> warnings) {
		if (samples.size() < 2) {
			throw new IllegalArgumentException("The file holds no readable time and altitude samples");
		}
		samples.sort(Comparator.comparingDouble(s -> s[0]));
		int n = samples.size();
		double[] time = new double[n];
		double[] altitude = new double[n];
		double[] velocity = hasVelocity ? new double[n] : null;
		for (int i = 0; i < n; i++) {
			time[i] = samples.get(i)[0];
			altitude[i] = samples.get(i)[1];
			if (velocity != null) {
				velocity[i] = samples.get(i)[2];
			}
		}
		return new FlightLog(source, time, altitude, velocity, events, warnings);
	}

	private static FlightLog withGapWarnings(FlightLog log) {
		List<double[]> gaps = log.gaps();
		if (gaps.isEmpty()) {
			return log;
		}
		List<String> warnings = new ArrayList<>(log.getWarnings());
		for (double[] gap : gaps) {
			warnings.add(String.format(Locale.ROOT, "No data from %.1f s to %.1f s (%.1f s missing).", gap[0], gap[1],
					gap[1] - gap[0]));
		}
		int n = log.size();
		double[] time = new double[n];
		double[] altitude = new double[n];
		double[] velocity = log.hasVelocity() ? new double[n] : null;
		for (int i = 0; i < n; i++) {
			time[i] = log.getTime(i);
			altitude[i] = log.getAltitude(i);
			if (velocity != null) {
				velocity[i] = log.getVelocity(i);
			}
		}
		return new FlightLog(log.getSource(), time, altitude, velocity, log.getEvents(), warnings);
	}

	private static double parse(List<String> row, Integer column) {
		if (column == null || column < 0 || column >= row.size()) {
			return Double.NaN;
		}
		try {
			return Double.parseDouble(row.get(column));
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}

}
