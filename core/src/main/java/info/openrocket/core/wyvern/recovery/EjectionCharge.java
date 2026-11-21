package info.openrocket.core.wyvern.recovery;

/**
 * Black powder ejection charge sizing from the ideal gas law, m = P V / (R T).
 * <p>
 * The gas constant and combustion temperature are the values commonly used for FFFFg
 * black powder in hobby rocketry: R = 266 in-lbf/(lbm R) and T = 3307 R. In inch, psi
 * and gram units they reduce to the familiar rule grams = 0.000516 x psi x cubic inches.
 * All methods here take and return SI units.
 */
public final class EjectionCharge {

	/** Specific gas constant of black powder combustion products, J/(kg K). 266 in-lbf/(lbm R). */
	public static final double BLACK_POWDER_GAS_CONSTANT = 266 * 0.112984829 / (0.45359237 * 5.0 / 9.0);

	/** Black powder combustion temperature in K. 3307 R. */
	public static final double BLACK_POWDER_COMBUSTION_TEMPERATURE = 3307 * 5.0 / 9.0;

	private EjectionCharge() {
	}

	/**
	 * Internal volume of a cylindrical bay.
	 *
	 * @param innerDiameter inner diameter in m
	 * @param length        length in m
	 * @return volume in m^3
	 */
	public static double cylinderVolume(double innerDiameter, double length) {
		RecoveryMath.requirePositive(length, "length");
		return circleArea(innerDiameter) * length;
	}

	/**
	 * Area of a circle from its diameter.
	 *
	 * @param diameter diameter in m
	 * @return area in m^2
	 */
	public static double circleArea(double diameter) {
		RecoveryMath.requirePositive(diameter, "diameter");
		return Math.PI * diameter * diameter / 4;
	}

	/**
	 * Black powder mass that pressurizes a volume to a target gauge pressure.
	 *
	 * @param pressure target pressure rise in Pa
	 * @param volume   free volume of the bay in m^3
	 * @return black powder mass in kg
	 */
	public static double blackPowderMass(double pressure, double volume) {
		RecoveryMath.requirePositive(pressure, "pressure");
		RecoveryMath.requirePositive(volume, "volume");
		return pressure * volume / (BLACK_POWDER_GAS_CONSTANT * BLACK_POWDER_COMBUSTION_TEMPERATURE);
	}

	/**
	 * Pressure rise produced by a black powder mass in a volume.
	 *
	 * @param mass   black powder mass in kg
	 * @param volume free volume of the bay in m^3
	 * @return pressure rise in Pa
	 */
	public static double pressureFromMass(double mass, double volume) {
		RecoveryMath.requirePositive(mass, "mass");
		RecoveryMath.requirePositive(volume, "volume");
		return mass * BLACK_POWDER_GAS_CONSTANT * BLACK_POWDER_COMBUSTION_TEMPERATURE / volume;
	}

	/**
	 * Force pushing the sections apart: pressure times bore area.
	 *
	 * @param pressure     pressure rise in Pa
	 * @param boreDiameter inner diameter of the airframe in m
	 * @return separation force in N
	 */
	public static double separationForce(double pressure, double boreDiameter) {
		RecoveryMath.requirePositive(pressure, "pressure");
		return pressure * circleArea(boreDiameter);
	}

	/**
	 * Backup charge mass: the primary charge increased by a fraction.
	 *
	 * @param primaryMass primary charge in kg
	 * @param increase    fractional increase, for example 0.25 for 25% more
	 * @return backup charge in kg
	 */
	public static double backupMass(double primaryMass, double increase) {
		RecoveryMath.requirePositive(primaryMass, "primaryMass");
		RecoveryMath.requireNonNegative(increase, "increase");
		return primaryMass * (1 + increase);
	}

	/**
	 * How many times over the separation force exceeds what the shear pins hold.
	 * A value above 1 means the pins break; common practice wants a clear margin.
	 *
	 * @param separationForce  force from the charge in N
	 * @param pinCount         number of shear pins in the joint
	 * @param pinShearStrength force that shears one pin, in N
	 * @return separation force divided by total pin strength
	 */
	public static double shearPinFactor(double separationForce, int pinCount, double pinShearStrength) {
		RecoveryMath.requirePositive(separationForce, "separationForce");
		RecoveryMath.requirePositive(pinShearStrength, "pinShearStrength");
		if (pinCount < 1) {
			throw new IllegalArgumentException("pinCount must be at least 1, was " + pinCount);
		}
		return separationForce / (pinCount * pinShearStrength);
	}
}
