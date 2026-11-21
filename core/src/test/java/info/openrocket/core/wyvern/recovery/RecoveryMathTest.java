package info.openrocket.core.wyvern.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Expected values are hand calculations in US customary units (slug, ft, lbf) with
 * sea level density 0.0023769 slug/ft^3, converted at the edges. The parachute cases
 * use DRACO's 2026-27 PDR vehicle: 31.30 lb descending, 96 in main (Cd 2.2) and
 * 15 in drogue (Cd 1.6).
 */
public class RecoveryMathTest {

	static final double LB = 0.45359237;        // kg per lbm
	static final double FT = 0.3048;            // m per ft
	static final double IN = 0.0254;            // m per in
	static final double LBF = 4.4482216152605;  // N per lbf
	static final double FT_LBF = LBF * FT;      // J per ft-lbf
	static final double RHO = 1.225;

	static final double MAIN_DRAG_AREA = 2.2 * Math.PI / 4 * Math.pow(96 * IN, 2);
	static final double DROGUE_DRAG_AREA = 1.6 * Math.PI / 4 * Math.pow(15 * IN, 2);

	@Test
	@DisplayName("drogue descent rate matches the hand value of 115.8 ft/s")
	public void testTerminalVelocityDrogue() {
		// v = sqrt(2 W / (rho CdA)) = sqrt(2 * 31.30 / (0.0023769 * 1.9635)) = 115.8 ft/s
		double v = RecoveryMath.terminalVelocity(31.30 * LB, RHO, DROGUE_DRAG_AREA);
		assertEquals(115.8, v / FT, 0.1);
	}

	@Test
	@DisplayName("main descent rate with both canopies open matches 15.3 ft/s")
	public void testTerminalVelocityMain() {
		// CdA = 110.58 + 1.96 = 112.54 ft^2, v = sqrt(2 * 31.30 / (0.0023769 * 112.54)) = 15.30 ft/s
		double v = RecoveryMath.terminalVelocity(31.30 * LB, RHO, MAIN_DRAG_AREA + DROGUE_DRAG_AREA);
		assertEquals(15.30, v / FT, 0.02);
	}

	@Test
	@DisplayName("drag area for a velocity inverts terminal velocity")
	public void testDragAreaRoundTrip() {
		double v = RecoveryMath.terminalVelocity(12.0, 1.1, 3.7);
		assertEquals(3.7, RecoveryMath.dragAreaForVelocity(12.0, 1.1, v), 1e-9);
	}

	@Test
	@DisplayName("kinetic energy of 10 lb at 20 ft/s is 62.16 ft-lbf")
	public void testKineticEnergy() {
		// 0.5 * (10 / 32.174) * 20^2 = 62.16 ft-lbf
		assertEquals(62.16, RecoveryMath.kineticEnergy(10 * LB, 20 * FT) / FT_LBF, 0.01);
		assertEquals(50.0, RecoveryMath.kineticEnergy(1, 10), 1e-12);
	}

	@Test
	@DisplayName("velocity for a kinetic energy limit inverts kinetic energy")
	public void testVelocityForKineticEnergy() {
		// 75 ft-lbf on 15 lb: v = sqrt(2 * 75 * 32.174 / 15) = 17.94 ft/s
		double v = RecoveryMath.velocityForKineticEnergy(15 * LB, 75 * FT_LBF);
		assertEquals(17.94, v / FT, 0.01);
		assertEquals(75.0, RecoveryMath.kineticEnergy(15 * LB, v) / FT_LBF, 1e-9);
	}

	@Test
	@DisplayName("opening force is Cx X1 q CdA")
	public void testOpeningForce() {
		// q = 0.5 * 0.0023769 * 118.9^2 = 16.80 psf, CdA = 110.58 ft^2
		// Cx 1.8, X1 1.0: 1.8 * 16.80 * 110.58 = 3344 lbf
		double f = RecoveryMath.openingForce(RHO, 118.9 * FT, MAIN_DRAG_AREA, 1.8, 1.0);
		assertEquals(3344, f / LBF, 3);
		// X1 scales the result linearly
		assertEquals(f * 0.4, RecoveryMath.openingForce(RHO, 118.9 * FT, MAIN_DRAG_AREA, 1.8, 0.4), 1e-9);
	}

	@Test
	@DisplayName("drift is wind speed times descent time")
	public void testDrift() {
		// 20 mph = 29.33 ft/s for 76.7 s = 2250 ft
		assertEquals(2250, RecoveryMath.driftDistance(20 * 0.44704, 76.7) / FT, 1);
	}

	@Test
	@DisplayName("load fraction is load over rating")
	public void testLoadFraction() {
		assertEquals(0.5933, RecoveryMath.loadFraction(2136 * LBF, 3600 * LBF), 1e-4);
	}

	@Test
	@DisplayName("non-physical inputs are rejected")
	public void testInvalidInputs() {
		assertThrows(IllegalArgumentException.class, () -> RecoveryMath.terminalVelocity(0, RHO, 1));
		assertThrows(IllegalArgumentException.class, () -> RecoveryMath.terminalVelocity(1, RHO, 0));
		assertThrows(IllegalArgumentException.class, () -> RecoveryMath.terminalVelocity(1, Double.NaN, 1));
		assertThrows(IllegalArgumentException.class, () -> RecoveryMath.openingForce(RHO, 30, 5, 1.8, 0));
		assertThrows(IllegalArgumentException.class, () -> RecoveryMath.openingForce(RHO, 30, 5, 1.8, 1.2));
		assertThrows(IllegalArgumentException.class, () -> RecoveryMath.loadFraction(10, 0));
	}
}
