package info.openrocket.core.wyvern.rules;

import java.util.ArrayList;
import java.util.List;

import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.wyvern.recovery.RecoveryMath;
import info.openrocket.core.wyvern.recovery.RecoveryReport;
import info.openrocket.core.wyvern.rules.RuleSet.Comparison;
import info.openrocket.core.wyvern.rules.RuleSet.Metric;
import info.openrocket.core.wyvern.rules.RuleSet.Rule;

/**
 * Checks one flight against a {@link RuleSet}.
 */
public final class RuleChecker {

	public enum Status {
		/** The requirement is met with room to spare. */
		PASS,
		/** The requirement is met, but the value is within the rule's warning margin of the limit. */
		CLOSE,
		/** The requirement is not met. */
		FAIL,
		/** The flight data does not contain what the rule measures. */
		UNKNOWN
	}

	/**
	 * @param rule   the rule
	 * @param value  the measured value in SI units, NaN if unknown
	 * @param status the outcome
	 */
	public record Result(Rule rule, double value, Status status) {
	}

	private RuleChecker() {
	}

	/**
	 * Check every rule of a rule set.
	 *
	 * @param rules  the rule set
	 * @param report recovery report of the flight
	 * @param branch flight data the report was made from
	 * @return one result per rule, in rule order
	 */
	public static List<Result> check(RuleSet rules, RecoveryReport report, FlightDataBranch branch) {
		List<Result> results = new ArrayList<>();
		for (Rule rule : rules.rules) {
			double value = measure(rule.metric, report, branch);
			results.add(new Result(rule, value, judge(rule, value)));
		}
		return results;
	}

	/** Decide the status of a value against a rule. */
	public static Status judge(Rule rule, double value) {
		if (Double.isNaN(value)) {
			return Status.UNKNOWN;
		}
		double limit = rule.limitSI();
		double margin = Math.abs(limit) * rule.warnMargin;
		if (rule.comparison == Comparison.AT_MOST) {
			if (value > limit) {
				return Status.FAIL;
			}
			return value > limit - margin ? Status.CLOSE : Status.PASS;
		}
		if (value < limit) {
			return Status.FAIL;
		}
		return value < limit + margin ? Status.CLOSE : Status.PASS;
	}

	/** Measure a metric in SI units; NaN if the data does not contain it. */
	public static double measure(Metric metric, RecoveryReport report, FlightDataBranch branch) {
		switch (metric) {
		case LANDING_KINETIC_ENERGY: {
			if (!report.reachedGround() || Double.isNaN(report.landingVelocity())) {
				return Double.NaN;
			}
			double worst = Double.NaN;
			for (RecoveryReport.SectionResult section : report.sections()) {
				double energy = RecoveryMath.kineticEnergy(section.mass(), report.landingVelocity());
				if (Double.isNaN(worst) || energy > worst) {
					worst = energy;
				}
			}
			return worst;
		}
		case DESCENT_TIME:
			return report.reachedGround() ? report.descentTime() : Double.NaN;
		case LANDING_DISTANCE:
			return report.reachedGround() ? report.landingDistance() : Double.NaN;
		case APOGEE:
			return report.apogeeAltitude();
		case RAIL_EXIT_VELOCITY:
			return atRailExit(branch, FlightDataType.TYPE_VELOCITY_TOTAL);
		case RAIL_EXIT_STABILITY:
			return atRailExit(branch, FlightDataType.TYPE_STABILITY);
		case MAX_MACH: {
			List<Double> mach = branch.get(FlightDataType.TYPE_MACH_NUMBER);
			if (mach == null || mach.isEmpty()) {
				return Double.NaN;
			}
			double max = Double.NaN;
			for (double m : mach) {
				if (!Double.isNaN(m) && (Double.isNaN(max) || m > max)) {
					max = m;
				}
			}
			return max;
		}
		default:
			return Double.NaN;
		}
	}

	/** First valid sample of a type at or after the launch rod clearance event. */
	private static double atRailExit(FlightDataBranch branch, FlightDataType type) {
		FlightEvent event = branch.getFirstEvent(FlightEvent.Type.LAUNCHROD);
		List<Double> time = branch.get(FlightDataType.TYPE_TIME);
		List<Double> values = branch.get(type);
		if (event == null || time == null || values == null) {
			return Double.NaN;
		}
		// Stability is undefined (NaN) for the first samples off the rail in some flights,
		// so take the first defined value within a short window after the event.
		for (int i = 0; i < Math.min(time.size(), values.size()); i++) {
			double t = time.get(i);
			if (t < event.getTime()) {
				continue;
			}
			if (t > event.getTime() + 0.5) {
				break;
			}
			if (!Double.isNaN(values.get(i))) {
				return values.get(i);
			}
		}
		return Double.NaN;
	}
}
