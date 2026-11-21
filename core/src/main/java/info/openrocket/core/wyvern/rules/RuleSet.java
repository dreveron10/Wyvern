package info.openrocket.core.wyvern.rules;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/**
 * A named list of competition or range rules that a flight is checked against.
 * <p>
 * Rule sets are JSON files under {@code wyvern/rules/} in the resources, one per rule
 * book and season, so a new season is a data change. Limits are written in the unit the
 * rule book uses and converted to SI when the file is loaded.
 */
public class RuleSet {

	/** Rule sets shipped with the application, by resource name without the extension. */
	public static final List<String> BUILT_IN = List.of("nasa-sl-2027");

	/** What a rule measures. */
	public enum Metric {
		/** Largest landing kinetic energy over all sections, in J. */
		LANDING_KINETIC_ENERGY,
		/** Time from apogee to landing, in s. */
		DESCENT_TIME,
		/** Lateral distance from the launch site at landing, in m. */
		LANDING_DISTANCE,
		/** Apogee altitude above the launch site, in m. */
		APOGEE,
		/** Speed when the rocket leaves the launch rail, in m/s. */
		RAIL_EXIT_VELOCITY,
		/** Static stability margin when the rocket leaves the launch rail, in calibers. */
		RAIL_EXIT_STABILITY,
		/** Highest Mach number of the flight. */
		MAX_MACH
	}

	public enum Comparison {
		AT_MOST, AT_LEAST
	}

	/** One requirement. */
	public static class Rule {
		public String id = "";
		public String title = "";
		public Metric metric;
		public Comparison comparison;
		/** Limit in the unit named by {@link #unit}, as written in the rule book. */
		public double limit;
		/** Unit of {@link #limit}; see {@link RuleSet#UNITS} for the accepted names. */
		public String unit = "";
		/** Fraction of the limit within which a passing value is flagged as close. */
		public double warnMargin = 0;

		/** The limit converted to SI. */
		public double limitSI() {
			return limit * UNITS.get(unit);
		}
	}

	/** Accepted unit names and their SI multipliers. */
	public static final Map<String, Double> UNITS = Map.ofEntries(
			Map.entry("", 1.0),
			Map.entry("cal", 1.0),
			Map.entry("s", 1.0),
			Map.entry("m", 1.0),
			Map.entry("ft", 0.3048),
			Map.entry("m/s", 1.0),
			Map.entry("ft/s", 0.3048),
			Map.entry("J", 1.0),
			Map.entry("ft-lbf", 1.3558179483314));

	public String name = "";
	/** Where the limits come from. */
	public String source = "";
	/** Whether the limits were checked against the published rule book. */
	public boolean verified = false;
	public List<Rule> rules = new ArrayList<>();

	/**
	 * Load a built-in rule set.
	 *
	 * @param resourceName name without path or extension, for example "nasa-sl-2027"
	 * @throws IllegalArgumentException if the rule set does not exist or is not valid
	 */
	public static RuleSet loadBuiltIn(String resourceName) {
		String path = "wyvern/rules/" + resourceName + ".json";
		try (InputStream is = RuleSet.class.getClassLoader().getResourceAsStream(path)) {
			if (is == null) {
				throw new IllegalArgumentException("Rule set not found: " + path);
			}
			return read(new InputStreamReader(is, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new IllegalArgumentException("Cannot read rule set " + path + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Read and validate a rule set from JSON.
	 *
	 * @throws IllegalArgumentException if the JSON is malformed or a rule is incomplete
	 */
	public static RuleSet read(Reader reader) {
		RuleSet set;
		try {
			set = new Gson().fromJson(reader, RuleSet.class);
		} catch (JsonParseException e) {
			throw new IllegalArgumentException("Invalid rule set: " + e.getMessage(), e);
		}
		if (set == null || set.rules == null) {
			throw new IllegalArgumentException("Invalid rule set: no rules");
		}
		for (Rule rule : set.rules) {
			if (rule.metric == null || rule.comparison == null) {
				throw new IllegalArgumentException("Rule '" + rule.id + "' needs a known metric and comparison");
			}
			if (rule.unit == null || !UNITS.containsKey(rule.unit)) {
				throw new IllegalArgumentException("Rule '" + rule.id + "' has an unknown unit: " + rule.unit);
			}
			if (Double.isNaN(rule.limit) || rule.warnMargin < 0 || rule.warnMargin >= 1) {
				throw new IllegalArgumentException("Rule '" + rule.id + "' has an invalid limit or warning margin");
			}
		}
		return set;
	}
}
