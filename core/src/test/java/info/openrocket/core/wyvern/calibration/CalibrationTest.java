package info.openrocket.core.wyvern.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.util.TestRockets;

public class CalibrationTest extends BaseTestCase {

	@Test
	public void testParachuteCdSingleChute() {
		// One 1 m2 chute at Cd 0.8 simulated at 5 m/s but measured at 4 m/s:
		// drag area must grow by (5/4)^2 = 1.5625, so Cd = 0.8 * 1.5625 = 1.25
		assertEquals(1.25, Calibration.parachuteCd(1.0, 0.8, 0.0, 5.0, 4.0), 1e-12);
		// Measured faster than simulated: Cd falls by (5/10)^2
		assertEquals(0.2, Calibration.parachuteCd(1.0, 0.8, 0.0, 5.0, 10.0), 1e-12);
	}

	@Test
	public void testParachuteCdWithDrogueOpen() {
		// Main 2 m2 at Cd 1.0 plus a drogue supplying 0.3 m2: simulated total 2.3 m2.
		// Measured 10% slower than simulated needs 2.3 / 0.9^2 = 2.8395 m2 in total;
		// the drogue keeps 0.3, so the main needs 2.5395 / 2 = 1.2698
		assertEquals((2.3 / 0.81 - 0.3) / 2.0, Calibration.parachuteCd(2.0, 2.3, 0.3, 5.0, 4.5), 1e-12);
	}

	@Test
	public void testParachuteCdRejectsBadInput() {
		assertTrue(Double.isNaN(Calibration.parachuteCd(1.0, 0.8, 0.0, Double.NaN, 4.0)));
		assertTrue(Double.isNaN(Calibration.parachuteCd(1.0, 0.8, 0.0, 5.0, 0.0)));
		assertTrue(Double.isNaN(Calibration.parachuteCd(0.0, 0.8, 0.0, 5.0, 4.0)));
		// The other chutes alone already give more drag than was measured
		assertTrue(Double.isNaN(Calibration.parachuteCd(1.0, 1.0, 0.9, 5.0, 10.0)));
	}

	@Test
	public void testSolveDragScaleOnKnownFunction() {
		// apogee = 1000 / k: 800 m is reached at k = 1.25, 2000 m at k = 0.5
		assertEquals(1.25, Calibration.solveDragScale(k -> 1000 / k, 800, 0.01), 1e-4);
		assertEquals(0.5, Calibration.solveDragScale(k -> 1000 / k, 2000, 0.01), 1e-4);
		assertEquals(1.0, Calibration.solveDragScale(k -> 1000 / k, 1000, 0.01), 0.0);
		// 1000 / 5 = 200 m is the lowest reachable apogee
		assertTrue(Double.isNaN(Calibration.solveDragScale(k -> 1000 / k, 100, 0.01)));
		assertTrue(Double.isNaN(Calibration.solveDragScale(k -> 1000 / k, 6000, 0.01)));
	}

	@Test
	public void testDragScaleRoundTripOnSimulation() throws SimulationException {
		Rocket rocket = TestRockets.makeEstesAlphaIII();
		Simulation simulation = new Simulation(rocket);
		simulation.setFlightConfigurationId(TestRockets.TEST_FCID_0);
		simulation.getOptions().setISAAtmosphere(true);
		simulation.getOptions().setTimeStep(0.05);
		simulation.getOptions().getAverageWindModel().setAverage(0);
		simulation.getOptions().getAverageWindModel().setStandardDeviation(0);

		simulation.simulate();
		double plain = simulation.getSimulatedData().getMaxAltitude();

		// More drag must lower the apogee
		assertNull(Calibration.dragScaleOf(simulation));
		Calibration.setDragScale(simulation, 1.3);
		assertNotNull(Calibration.dragScaleOf(simulation));
		simulation.simulate();
		double draggy = simulation.getSimulatedData().getMaxAltitude();
		assertTrue(draggy < plain, "apogee with drag x1.3 (" + draggy + ") should be below " + plain);

		// Asking for the apogee that x1.3 produced must give x1.3 back, whatever scale is set now
		Calibration.setDragScale(simulation, 0.7);
		double solved = Calibration.dragScaleForApogee(simulation, draggy);
		assertEquals(1.3, solved, 0.01);
		assertEquals(0.7, Calibration.dragScaleOf(simulation).getScale(), 0.0);
	}
}
