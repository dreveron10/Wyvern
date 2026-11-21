package info.openrocket.core.wyvern.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.wyvern.recovery.RecoveryAnalyzer;
import info.openrocket.core.wyvern.recovery.RecoveryReport;
import info.openrocket.core.wyvern.recovery.RecoverySetup;
import info.openrocket.core.wyvern.rules.RuleChecker.Status;
import info.openrocket.core.wyvern.rules.RuleSet.Comparison;
import info.openrocket.core.wyvern.rules.RuleSet.Metric;
import info.openrocket.core.wyvern.rules.RuleSet.Rule;

public class RuleCheckerTest extends BaseTestCase {

	private static Rule rule(Metric metric, Comparison comparison, double limit, String unit, double warnMargin) {
		Rule rule = new Rule();
		rule.id = metric.name();
		rule.metric = metric;
		rule.comparison = comparison;
		rule.limit = limit;
		rule.unit = unit;
		rule.warnMargin = warnMargin;
		return rule;
	}

	@Test
	@DisplayName("the built-in NASA Student Launch rule set loads and converts its limits to SI")
	public void testBuiltInRuleSet() {
		for (String name : RuleSet.BUILT_IN) {
			assertFalse(RuleSet.loadBuiltIn(name).rules.isEmpty());
		}
		RuleSet set = RuleSet.loadBuiltIn("nasa-sl-2027");
		Rule ke = set.rules.stream().filter(r -> r.id.equals("ke-landing")).findFirst().orElseThrow();
		assertEquals(Metric.LANDING_KINETIC_ENERGY, ke.metric);
		// 75 ft-lbf = 101.69 J
		assertEquals(101.69, ke.limitSI(), 0.01);
		Rule drift = set.rules.stream().filter(r -> r.id.equals("drift")).findFirst().orElseThrow();
		// 2500 ft = 762 m
		assertEquals(762.0, drift.limitSI(), 1e-9);
		assertFalse(set.verified);
		assertThrows(IllegalArgumentException.class, () -> RuleSet.loadBuiltIn("no-such-rules"));
	}

	@Test
	@DisplayName("invalid rule files are rejected with a reason")
	public void testInvalidRuleSets() {
		assertThrows(IllegalArgumentException.class, () -> RuleSet.read(new StringReader("{")));
		assertThrows(IllegalArgumentException.class, () -> RuleSet.read(new StringReader("{\"name\":\"x\",\"rules\":null}")));
		assertThrows(IllegalArgumentException.class, () -> RuleSet.read(new StringReader(
				"{\"rules\":[{\"id\":\"a\",\"metric\":\"APOGEE\",\"comparison\":\"AT_MOST\",\"limit\":1,\"unit\":\"furlong\"}]}")));
		assertThrows(IllegalArgumentException.class, () -> RuleSet.read(new StringReader(
				"{\"rules\":[{\"id\":\"a\",\"metric\":\"NOT_A_METRIC\",\"comparison\":\"AT_MOST\",\"limit\":1,\"unit\":\"m\"}]}")));
	}

	@Test
	@DisplayName("values are judged against upper and lower limits with a warning band")
	public void testJudge() {
		Rule atMost = rule(Metric.DESCENT_TIME, Comparison.AT_MOST, 100, "s", 0.1);
		assertEquals(Status.PASS, RuleChecker.judge(atMost, 80));
		assertEquals(Status.PASS, RuleChecker.judge(atMost, 90));
		assertEquals(Status.CLOSE, RuleChecker.judge(atMost, 95));
		assertEquals(Status.CLOSE, RuleChecker.judge(atMost, 100));
		assertEquals(Status.FAIL, RuleChecker.judge(atMost, 100.1));
		assertEquals(Status.UNKNOWN, RuleChecker.judge(atMost, Double.NaN));

		Rule atLeast = rule(Metric.RAIL_EXIT_VELOCITY, Comparison.AT_LEAST, 52, "ft/s", 0.1);
		double fps = 0.3048;
		assertEquals(Status.FAIL, RuleChecker.judge(atLeast, 51.9 * fps));
		assertEquals(Status.CLOSE, RuleChecker.judge(atLeast, 52 * fps));
		assertEquals(Status.CLOSE, RuleChecker.judge(atLeast, 56 * fps));
		assertEquals(Status.PASS, RuleChecker.judge(atLeast, 58 * fps));
	}

	/** Apogee 1500 m at 10 s, steady 6 m/s descent, landing at 90 s, 500 m from the pad. */
	private static FlightDataBranch flight() {
		FlightDataBranch b = new FlightDataBranch("test", FlightDataType.TYPE_TIME, FlightDataType.TYPE_ALTITUDE,
				FlightDataType.TYPE_VELOCITY_Z, FlightDataType.TYPE_VELOCITY_TOTAL, FlightDataType.TYPE_MASS,
				FlightDataType.TYPE_POSITION_XY, FlightDataType.TYPE_STABILITY, FlightDataType.TYPE_MACH_NUMBER);
		double[][] points = {
				// t, altitude, vz, mass, xy, stability, mach
				{ 0.0, 0, 0, 12, 0, Double.NaN, 0 },
				{ 0.3, 3, 20, 12, 0, Double.NaN, 0.06 },
				{ 0.4, 5, 25, 12, 0, 2.4, 0.07 },
				{ 3.0, 400, 250, 10, 5, 3.0, 0.74 },
				{ 10.0, 1500, 0, 10, 60, 3.5, 0.02 },
				{ 12.0, 1490, -6, 10, 70, Double.NaN, 0.02 },
				{ 90.0, 0, -6, 10, 500, Double.NaN, 0.02 } };
		for (double[] p : points) {
			b.addPoint();
			b.setValue(FlightDataType.TYPE_TIME, p[0]);
			b.setValue(FlightDataType.TYPE_ALTITUDE, p[1]);
			b.setValue(FlightDataType.TYPE_VELOCITY_Z, p[2]);
			b.setValue(FlightDataType.TYPE_VELOCITY_TOTAL, Math.abs(p[2]));
			b.setValue(FlightDataType.TYPE_MASS, p[3]);
			b.setValue(FlightDataType.TYPE_POSITION_XY, p[4]);
			b.setValue(FlightDataType.TYPE_STABILITY, p[5]);
			b.setValue(FlightDataType.TYPE_MACH_NUMBER, p[6]);
		}
		b.addEvent(new FlightEvent(FlightEvent.Type.LAUNCHROD, 0.3));
		b.addEvent(new FlightEvent(FlightEvent.Type.APOGEE, 10));
		b.addEvent(new FlightEvent(FlightEvent.Type.GROUND_HIT, 90));
		return b;
	}

	@Test
	@DisplayName("every metric is measured from the flight")
	public void testMeasure() {
		FlightDataBranch branch = flight();
		RecoverySetup setup = new RecoverySetup();
		RecoverySetup.Section light = new RecoverySetup.Section("Light");
		light.extraMass = 3;
		RecoverySetup.Section heavy = new RecoverySetup.Section("Heavy");
		heavy.extraMass = 7;
		setup.sections.add(light);
		setup.sections.add(heavy);
		RecoveryReport report = RecoveryAnalyzer.analyze(branch, null, setup);

		// Heaviest section: 0.5 * 7 * 6^2 = 126 J
		assertEquals(126.0, RuleChecker.measure(Metric.LANDING_KINETIC_ENERGY, report, branch), 1e-9);
		assertEquals(80.0, RuleChecker.measure(Metric.DESCENT_TIME, report, branch), 1e-9);
		assertEquals(500.0, RuleChecker.measure(Metric.LANDING_DISTANCE, report, branch), 1e-9);
		assertEquals(1500.0, RuleChecker.measure(Metric.APOGEE, report, branch), 1e-9);
		assertEquals(20.0, RuleChecker.measure(Metric.RAIL_EXIT_VELOCITY, report, branch), 1e-9);
		// Stability is undefined at the rail exit sample, so the next sample is used
		assertEquals(2.4, RuleChecker.measure(Metric.RAIL_EXIT_STABILITY, report, branch), 1e-9);
		assertEquals(0.74, RuleChecker.measure(Metric.MAX_MACH, report, branch), 1e-9);

		RuleSet set = new RuleSet();
		set.rules.add(rule(Metric.LANDING_KINETIC_ENERGY, Comparison.AT_MOST, 75, "ft-lbf", 0.1));
		set.rules.add(rule(Metric.DESCENT_TIME, Comparison.AT_MOST, 100, "s", 0.1));
		set.rules.add(rule(Metric.RAIL_EXIT_STABILITY, Comparison.AT_LEAST, 2.0, "cal", 0.1));
		List<RuleChecker.Result> results = RuleChecker.check(set, report, branch);
		// 126 J is over 101.69 J
		assertEquals(Status.FAIL, results.get(0).status());
		assertEquals(Status.PASS, results.get(1).status());
		assertEquals(Status.PASS, results.get(2).status());
		assertEquals(126.0, results.get(0).value(), 1e-9);
	}

	@Test
	@DisplayName("a flight that never lands leaves the landing rules unknown")
	public void testNoLanding() {
		FlightDataBranch landed = flight();
		FlightDataBranch b = new FlightDataBranch("partial", FlightDataType.TYPE_TIME, FlightDataType.TYPE_ALTITUDE,
				FlightDataType.TYPE_VELOCITY_Z);
		for (int i = 0; i < landed.getLength() - 1; i++) {
			b.addPoint();
			b.setValue(FlightDataType.TYPE_TIME, landed.get(FlightDataType.TYPE_TIME).get(i));
			b.setValue(FlightDataType.TYPE_ALTITUDE, landed.get(FlightDataType.TYPE_ALTITUDE).get(i));
			b.setValue(FlightDataType.TYPE_VELOCITY_Z, landed.get(FlightDataType.TYPE_VELOCITY_Z).get(i));
		}
		RecoveryReport report = RecoveryAnalyzer.analyze(b, null, new RecoverySetup());

		assertTrue(Double.isNaN(RuleChecker.measure(Metric.LANDING_KINETIC_ENERGY, report, b)));
		assertTrue(Double.isNaN(RuleChecker.measure(Metric.DESCENT_TIME, report, b)));
		assertTrue(Double.isNaN(RuleChecker.measure(Metric.LANDING_DISTANCE, report, b)));
		assertTrue(Double.isNaN(RuleChecker.measure(Metric.RAIL_EXIT_VELOCITY, report, b)));
		assertTrue(Double.isNaN(RuleChecker.measure(Metric.MAX_MACH, report, b)));
		assertEquals(1500.0, RuleChecker.measure(Metric.APOGEE, report, b), 1e-9);
	}
}
