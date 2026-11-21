package info.openrocket.core.wyvern.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Expected values come from the hobby rule grams = 0.000516 x psi x in^3, worked by
 * hand for DRACO's 2026-27 PDR bays (5.38 in bore, 15 psi). The PDR flysheet lists
 * the same charges rounded up to 1.4 g and 1.8 g, and 341 lbf of separation force.
 */
public class EjectionChargeTest {

	static final double IN = 0.0254;
	static final double PSI = 6894.757293168;
	static final double LBF = 4.4482216152605;
	static final double GRAM = 1e-3;
	static final double BORE = 5.38 * IN;

	@Test
	@DisplayName("drogue bay charge is 1.34 g at 15 psi")
	public void testDrogueBayCharge() {
		// V = pi/4 * 5.38^2 * 7.61 = 173.0 in^3, 0.000516 * 15 * 173.0 = 1.339 g
		double volume = EjectionCharge.cylinderVolume(BORE, 7.61 * IN);
		assertEquals(173.0, volume / Math.pow(IN, 3), 0.1);
		assertEquals(1.339, EjectionCharge.blackPowderMass(15 * PSI, volume) / GRAM, 0.002);
	}

	@Test
	@DisplayName("main bay charge is 1.76 g at 15 psi")
	public void testMainBayCharge() {
		// V = pi/4 * 5.38^2 * 9.99 = 227.1 in^3, 0.000516 * 15 * 227.1 = 1.758 g
		double volume = EjectionCharge.cylinderVolume(BORE, 9.99 * IN);
		assertEquals(1.758, EjectionCharge.blackPowderMass(15 * PSI, volume) / GRAM, 0.002);
	}

	@Test
	@DisplayName("pressure from mass inverts mass from pressure")
	public void testPressureRoundTrip() {
		double volume = EjectionCharge.cylinderVolume(BORE, 9.99 * IN);
		double mass = EjectionCharge.blackPowderMass(15 * PSI, volume);
		assertEquals(15.0, EjectionCharge.pressureFromMass(mass, volume) / PSI, 1e-9);
	}

	@Test
	@DisplayName("separation force is 341 lbf for 15 psi on a 5.38 in bore")
	public void testSeparationForce() {
		// 15 psi * 22.73 in^2 = 341.0 lbf
		assertEquals(341.0, EjectionCharge.separationForce(15 * PSI, BORE) / LBF, 0.1);
	}

	@Test
	@DisplayName("backup charge adds the given fraction")
	public void testBackupMass() {
		assertEquals(1.25 * GRAM, EjectionCharge.backupMass(1.0 * GRAM, 0.25), 1e-12);
		assertEquals(1.0 * GRAM, EjectionCharge.backupMass(1.0 * GRAM, 0), 1e-12);
	}

	@Test
	@DisplayName("shear pin factor compares separation force to total pin strength")
	public void testShearPinFactor() {
		// 341 lbf against three pins of 40 lbf each: 341 / 120 = 2.84
		assertEquals(2.84, EjectionCharge.shearPinFactor(341 * LBF, 3, 40 * LBF), 0.005);
		assertThrows(IllegalArgumentException.class, () -> EjectionCharge.shearPinFactor(341 * LBF, 0, 40 * LBF));
	}

	@Test
	@DisplayName("non-physical inputs are rejected")
	public void testInvalidInputs() {
		assertThrows(IllegalArgumentException.class, () -> EjectionCharge.blackPowderMass(0, 1));
		assertThrows(IllegalArgumentException.class, () -> EjectionCharge.blackPowderMass(1, -1));
		assertThrows(IllegalArgumentException.class, () -> EjectionCharge.cylinderVolume(0, 1));
	}
}
