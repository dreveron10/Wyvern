package info.openrocket.core.wyvern.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.util.TestRockets;

public class RecoveryAnalyzerTest extends BaseTestCase {

	private static final double RHO = 1.2;
	private static final double MASS = 10.0;

	private static Parachute parachute(String name, double diameter, double cd, boolean drogue) {
		Parachute chute = new Parachute();
		chute.setName(name);
		chute.setDiameter(diameter);
		chute.setCD(cd);
		chute.setDrogue(drogue);
		return chute;
	}

	private static void point(FlightDataBranch b, double t, double alt, double vz, double xy) {
		b.addPoint();
		b.setValue(FlightDataType.TYPE_TIME, t);
		b.setValue(FlightDataType.TYPE_ALTITUDE, alt);
		b.setValue(FlightDataType.TYPE_VELOCITY_Z, vz);
		b.setValue(FlightDataType.TYPE_VELOCITY_TOTAL, Math.abs(vz));
		b.setValue(FlightDataType.TYPE_AIR_DENSITY, RHO);
		b.setValue(FlightDataType.TYPE_MASS, MASS);
		b.setValue(FlightDataType.TYPE_POSITION_XY, xy);
	}

	/**
	 * A hand-built dual deployment flight: apogee 1000 m at 10 s, drogue at 12 s while
	 * falling at 20 m/s, steady 25 m/s under drogue, main at 40 s (300 m), steady 5 m/s
	 * under main, landing at 100 s, 400 m from the pad.
	 */
	private static FlightDataBranch dualDeployBranch(Parachute drogue, Parachute main) {
		FlightDataBranch b = new FlightDataBranch("test", FlightDataType.TYPE_TIME, FlightDataType.TYPE_ALTITUDE,
				FlightDataType.TYPE_VELOCITY_Z, FlightDataType.TYPE_VELOCITY_TOTAL, FlightDataType.TYPE_AIR_DENSITY,
				FlightDataType.TYPE_MASS, FlightDataType.TYPE_POSITION_XY);
		point(b, 0, 0, 0, 0);
		point(b, 10, 1000, 0, 50);
		point(b, 12, 980, -20, 60);
		point(b, 14, 950, -25, 70);
		point(b, 40, 300, -25, 200);
		point(b, 42, 280, -5, 210);
		point(b, 100, 0, -5, 400);
		b.addEvent(new FlightEvent(FlightEvent.Type.APOGEE, 10));
		b.addEvent(new FlightEvent(FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT, 12, drogue));
		b.addEvent(new FlightEvent(FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT, 40, main));
		b.addEvent(new FlightEvent(FlightEvent.Type.GROUND_HIT, 100));
		return b;
	}

	@Test
	@DisplayName("dual deployment flight gives two phases with hand-checked numbers")
	public void testDualDeployment() {
		Parachute drogue = parachute("Drogue", 0.5, 1.5, true);
		Parachute main = parachute("Main", 2.0, 2.0, false);
		RecoverySetup setup = new RecoverySetup();
		setup.openingForceCoeff = 1.5;
		setup.forceReductionFactor = 1.0;
		setup.safetyFactor = 2.0;
		RecoverySetup.Section nose = new RecoverySetup.Section("Nose");
		nose.extraMass = 4.0;
		RecoverySetup.Section aft = new RecoverySetup.Section("Aft");
		aft.extraMass = 5.0;
		setup.sections.add(nose);
		setup.sections.add(aft);
		setup.hardware.add(new RecoverySetup.HardwareItem("Cord", 10000));

		RecoveryReport r = RecoveryAnalyzer.analyze(dualDeployBranch(drogue, main), null, setup);

		assertEquals(10, r.apogeeTime(), 1e-9);
		assertEquals(1000, r.apogeeAltitude(), 1e-9);
		assertTrue(r.reachedGround());
		assertEquals(100, r.landingTime(), 1e-9);
		assertEquals(90, r.descentTime(), 1e-9);
		assertEquals(400, r.landingDistance(), 1e-9);
		assertEquals(5, r.landingVelocity(), 1e-9);
		assertEquals(MASS, r.descentMass(), 1e-9);
		assertEquals(2, r.phases().size());

		// Drogue: CdA = 1.5 * pi/4 * 0.5^2 = 0.29452 m^2
		// F = 1.5 * (0.5 * 1.2 * 20^2) * 0.29452 = 106.03 N
		RecoveryReport.Phase p0 = r.phases().get(0);
		assertEquals("Drogue", p0.deviceName());
		assertTrue(p0.drogue());
		assertEquals(980, p0.deployAltitude(), 1e-9);
		assertEquals(20, p0.deployVelocity(), 1e-9);
		assertEquals(0.29452, p0.deviceDragArea(), 1e-5);
		assertEquals(106.03, p0.openingForce(), 0.01);
		assertEquals(28, p0.duration(), 1e-9);
		assertEquals(25, p0.endVelocity(), 1e-9);
		// v = sqrt(2 * 10 * 9.80665 / (1.2 * 0.29452)) = 23.56 m/s
		assertEquals(23.56, p0.terminalVelocity(), 0.01);

		// Main: CdA = 2.0 * pi = 6.28319 m^2, opening at 25 m/s
		// F = 1.5 * (0.5 * 1.2 * 25^2) * 6.28319 = 3534.3 N
		RecoveryReport.Phase p1 = r.phases().get(1);
		assertEquals("Main", p1.deviceName());
		assertFalse(p1.drogue());
		assertEquals(6.28319, p1.deviceDragArea(), 1e-5);
		assertEquals(6.28319 + 0.29452, p1.totalDragArea(), 1e-5);
		assertEquals(3534.3, p1.openingForce(), 0.1);
		assertEquals(5, p1.endVelocity(), 1e-9);
		// v = sqrt(2 * 10 * 9.80665 / (1.2 * 6.57771)) = 4.985 m/s
		assertEquals(4.985, p1.terminalVelocity(), 0.001);

		assertEquals(3534.3, r.maxOpeningForce(), 0.1);
		assertEquals(7068.6, r.designLoad(), 0.2);
		assertEquals(0.70686, r.hardware().get(0).loadFraction(), 1e-4);

		// KE = 1/2 m v^2: nose 4 kg at 25 and 5 m/s, aft 5 kg
		assertEquals(1250.0, r.sections().get(0).kineticEnergies().get(0), 1e-9);
		assertEquals(50.0, r.sections().get(0).landingEnergy(), 1e-9);
		assertEquals(1562.5, r.sections().get(1).kineticEnergies().get(0), 1e-9);
		assertEquals(62.5, r.sections().get(1).landingEnergy(), 1e-9);
		assertEquals(1.0, r.unassignedMass(), 1e-9);
	}

	@Test
	@DisplayName("flight data without events falls back to the highest sample and the last point")
	public void testNoEvents() {
		FlightDataBranch b = dualDeployBranch(parachute("D", 0.5, 1.5, true), parachute("M", 2, 2, false));
		FlightDataBranch bare = new FlightDataBranch("bare", FlightDataType.TYPE_TIME, FlightDataType.TYPE_ALTITUDE,
				FlightDataType.TYPE_VELOCITY_Z);
		for (int i = 0; i < b.getLength(); i++) {
			bare.addPoint();
			bare.setValue(FlightDataType.TYPE_TIME, b.get(FlightDataType.TYPE_TIME).get(i));
			bare.setValue(FlightDataType.TYPE_ALTITUDE, b.get(FlightDataType.TYPE_ALTITUDE).get(i));
			bare.setValue(FlightDataType.TYPE_VELOCITY_Z, b.get(FlightDataType.TYPE_VELOCITY_Z).get(i));
		}

		RecoveryReport r = RecoveryAnalyzer.analyze(bare, null, new RecoverySetup());

		assertEquals(10, r.apogeeTime(), 1e-9);
		assertFalse(r.reachedGround());
		assertEquals(100, r.landingTime(), 1e-9);
		assertEquals(5, r.landingVelocity(), 1e-9);
		assertTrue(r.phases().isEmpty());
		assertTrue(Double.isNaN(r.maxOpeningForce()));
		assertTrue(Double.isNaN(r.landingDistance()));
	}

	@Test
	@DisplayName("values are interpolated linearly and clamped at the ends")
	public void testValueAt() {
		FlightDataBranch b = dualDeployBranch(parachute("D", 0.5, 1.5, true), parachute("M", 2, 2, false));
		assertEquals(500, RecoveryAnalyzer.valueAt(b, FlightDataType.TYPE_ALTITUDE, 5), 1e-9);
		assertEquals(990, RecoveryAnalyzer.valueAt(b, FlightDataType.TYPE_ALTITUDE, 11), 1e-9);
		assertEquals(0, RecoveryAnalyzer.valueAt(b, FlightDataType.TYPE_ALTITUDE, -3), 1e-9);
		assertEquals(0, RecoveryAnalyzer.valueAt(b, FlightDataType.TYPE_ALTITUDE, 500), 1e-9);
		assertTrue(Double.isNaN(RecoveryAnalyzer.valueAt(b, FlightDataType.TYPE_WIND_VELOCITY, 5)));
	}

	@Test
	@DisplayName("too little flight data is rejected")
	public void testEmptyBranch() {
		FlightDataBranch b = new FlightDataBranch("empty", FlightDataType.TYPE_TIME, FlightDataType.TYPE_ALTITUDE);
		assertThrows(IllegalArgumentException.class, () -> RecoveryAnalyzer.analyze(b, null, new RecoverySetup()));
	}

	@Test
	@DisplayName("charges and pad stay time come from the setup alone")
	public void testChargesAndBatteries() {
		RecoverySetup setup = new RecoverySetup();
		RecoverySetup.ChargeBay bay = new RecoverySetup.ChargeBay("Drogue bay", 5.38 * 0.0254, 7.61 * 0.0254);
		bay.shearPinCount = 3;
		bay.shearPinStrength = 40 * 4.4482216152605;
		setup.chargeBays.add(bay);
		setup.chargeBays.add(new RecoverySetup.ChargeBay("Not filled in", 0, 0));
		// 400 mAh at 50 mA with 80% usable = 6.4 h; 1000 mAh at 100 mA = 8 h
		setup.batteries.add(new RecoverySetup.Battery("Blue Raven 1S", 0.4, 0.05));
		setup.batteries.add(new RecoverySetup.Battery("Fluctus 2S", 1.0, 0.1));
		setup.batteries.add(new RecoverySetup.Battery("Not filled in", 0, 0));

		var charges = RecoveryAnalyzer.charges(setup);
		// Same bay as EjectionChargeTest: 1.339 g primary, 25% more for the backup, 341 lbf
		assertEquals(1.339e-3, charges.get(0).primaryMass(), 2e-6);
		assertEquals(1.674e-3, charges.get(0).backupMass(), 3e-6);
		assertEquals(341.0 * 4.4482216152605, charges.get(0).separationForce(), 0.5);
		assertEquals(2.84, charges.get(0).shearPinFactor(), 0.005);
		assertTrue(Double.isNaN(charges.get(1).primaryMass()));

		var batteries = RecoveryAnalyzer.batteries(setup);
		assertEquals(6.4 * 3600, batteries.get(0).endurance(), 1e-6);
		assertEquals(8.0 * 3600, batteries.get(1).endurance(), 1e-6);
		assertTrue(Double.isNaN(batteries.get(2).endurance()));
		assertEquals(6.4 * 3600, RecoveryAnalyzer.padStayTime(setup), 1e-6);
		assertTrue(Double.isNaN(RecoveryAnalyzer.padStayTime(new RecoverySetup())));
	}

	@Test
	@DisplayName("a simulated flight agrees with the closed-form descent rate and accounts for all mass")
	public void testSimulatedFlight() throws SimulationException {
		Rocket rocket = TestRockets.makeEstesAlphaIII();
		Simulation sim = new Simulation(rocket);
		sim.getOptions().setISAAtmosphere(true);
		sim.getOptions().setTimeStep(0.05);
		sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
		sim.simulate();

		FlightConfiguration config = sim.getActiveConfiguration();
		RecoverySetup setup = new RecoverySetup();
		RecoverySetup.Section all = new RecoverySetup.Section("Whole rocket");
		AxialStage stage = rocket.getStage(0);
		for (RocketComponent child : stage.getChildren()) {
			all.componentIds.add(child.getID().toString());
		}
		setup.sections.add(all);

		FlightDataBranch branch = sim.getSimulatedData().getBranch(0);
		RecoveryReport r = RecoveryAnalyzer.analyze(branch, config, setup);

		assertTrue(r.reachedGround());
		assertEquals(1, r.phases().size());
		assertEquals(sim.getSimulatedData().getFlightTime() - sim.getSimulatedData().getTimeToApogee(),
				r.descentTime(), 0.1);
		assertEquals(sim.getSimulatedData().getGroundHitVelocity(), r.landingVelocity(), 0.05);

		// The simulator integrates the same drag model, so the closed form must land within 2%
		RecoveryReport.Phase phase = r.phases().get(0);
		assertEquals(phase.endVelocity(), phase.terminalVelocity(), 0.02 * phase.endVelocity());

		// Components plus the burnt-out motor must add up to the simulated descent mass
		assertEquals(r.descentMass(), r.sections().get(0).mass(), 1e-4);
		assertEquals(0, r.unassignedMass(), 1e-4);
		assertEquals(0.5 * r.descentMass() * Math.pow(r.landingVelocity(), 2),
				r.sections().get(0).landingEnergy(), 1e-3);
	}

	@Test
	@DisplayName("a stage mass override is spread over the components in proportion")
	public void testStageMassOverride() {
		Rocket rocket = TestRockets.makeEstesAlphaIII();
		AxialStage stage = rocket.getStage(0);
		FlightConfiguration config = rocket.getFlightConfiguration(TestRockets.TEST_FCID_0);

		double modeled = 0;
		for (RocketComponent child : stage.getChildren()) {
			modeled += child.getSectionMass();
		}
		RocketComponent first = stage.getChild(0);
		double firstModeled = first.getSectionMass();

		stage.setMassOverridden(true);
		stage.setSubcomponentsOverriddenMass(true);
		stage.setOverrideMass(2 * modeled);

		// Doubling the stage mass doubles every component's share
		assertEquals(2 * firstModeled, RecoveryAnalyzer.componentMass(first), 1e-9);

		RecoverySetup.Section all = new RecoverySetup.Section("Whole rocket");
		for (RocketComponent child : stage.getChildren()) {
			all.componentIds.add(child.getID().toString());
		}
		double burnoutMotor = config.getActiveMotors().iterator().next().getMotor().getBurnoutMass();
		assertEquals(2 * modeled + burnoutMotor, RecoveryAnalyzer.sectionMass(all, config), 1e-9);
	}
}
