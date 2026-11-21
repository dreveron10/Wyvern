package info.openrocket.core.wyvern.recovery;

/**
 * Closed-form recovery calculations. All values are in SI units (kg, m, s, N, J, Pa),
 * as everywhere else in the core module.
 * <p>
 * "Drag area" always means the sum of Cd times reference area over every deployed
 * recovery device, in square meters.
 */
public final class RecoveryMath {

	/** Standard gravity in m/s^2. */
	public static final double G0 = 9.80665;

	/** ISA sea level air density in kg/m^3. */
	public static final double STANDARD_AIR_DENSITY = 1.225;

	private RecoveryMath() {
	}

	/**
	 * Dynamic pressure q = 1/2 rho v^2.
	 *
	 * @param airDensity air density in kg/m^3
	 * @param velocity   speed relative to the air in m/s
	 * @return dynamic pressure in Pa
	 */
	public static double dynamicPressure(double airDensity, double velocity) {
		requirePositive(airDensity, "airDensity");
		return 0.5 * airDensity * velocity * velocity;
	}

	/**
	 * Steady descent speed at which drag equals weight: v = sqrt(2 m g / (rho CdA)).
	 *
	 * @param mass       descending mass in kg
	 * @param airDensity air density in kg/m^3
	 * @param dragArea   sum of Cd times area of the deployed devices, in m^2
	 * @return descent speed in m/s (positive)
	 */
	public static double terminalVelocity(double mass, double airDensity, double dragArea) {
		requirePositive(mass, "mass");
		requirePositive(airDensity, "airDensity");
		requirePositive(dragArea, "dragArea");
		return Math.sqrt(2 * mass * G0 / (airDensity * dragArea));
	}

	/**
	 * Drag area that produces a given steady descent speed. This is the inverse of
	 * {@link #terminalVelocity}, used to size a parachute for a target descent rate or
	 * to back out the real drag area from a measured descent rate.
	 *
	 * @param mass       descending mass in kg
	 * @param airDensity air density in kg/m^3
	 * @param velocity   steady descent speed in m/s
	 * @return drag area (Cd times area) in m^2
	 */
	public static double dragAreaForVelocity(double mass, double airDensity, double velocity) {
		requirePositive(mass, "mass");
		requirePositive(airDensity, "airDensity");
		requirePositive(velocity, "velocity");
		return 2 * mass * G0 / (airDensity * velocity * velocity);
	}

	/**
	 * Kinetic energy 1/2 m v^2.
	 *
	 * @param mass     mass in kg
	 * @param velocity speed in m/s
	 * @return kinetic energy in J
	 */
	public static double kineticEnergy(double mass, double velocity) {
		requireNonNegative(mass, "mass");
		return 0.5 * mass * velocity * velocity;
	}

	/**
	 * Fastest speed at which a mass stays at or under a kinetic energy limit.
	 *
	 * @param mass        mass in kg
	 * @param energyLimit kinetic energy limit in J
	 * @return speed in m/s
	 */
	public static double velocityForKineticEnergy(double mass, double energyLimit) {
		requirePositive(mass, "mass");
		requireNonNegative(energyLimit, "energyLimit");
		return Math.sqrt(2 * energyLimit / mass);
	}

	/**
	 * Peak parachute opening force by the Knacke method: F = Cx X1 q CdA, with q taken
	 * at the moment the canopy starts to inflate.
	 *
	 * @param airDensity           air density in kg/m^3
	 * @param velocity             speed at line stretch in m/s
	 * @param dragArea             Cd times area of the opening canopy, in m^2
	 * @param openingForceCoeff    Cx, the opening force coefficient at infinite mass
	 *                             (canopy dependent, typically 1.0 to 1.8)
	 * @param forceReductionFactor X1, the opening force reduction factor, between 0 and 1.
	 *                             Use 1 for the conservative infinite mass case.
	 * @return peak opening force in N
	 */
	public static double openingForce(double airDensity, double velocity, double dragArea,
			double openingForceCoeff, double forceReductionFactor) {
		requirePositive(dragArea, "dragArea");
		requirePositive(openingForceCoeff, "openingForceCoeff");
		if (!(forceReductionFactor > 0 && forceReductionFactor <= 1)) {
			throw new IllegalArgumentException("forceReductionFactor must be in (0, 1], was " + forceReductionFactor);
		}
		return openingForceCoeff * forceReductionFactor * dynamicPressure(airDensity, velocity) * dragArea;
	}

	/**
	 * Horizontal drift of a body that descends at the wind speed for a given time.
	 *
	 * @param windSpeed   constant wind speed in m/s
	 * @param descentTime time under canopy in s
	 * @return drift distance in m
	 */
	public static double driftDistance(double windSpeed, double descentTime) {
		requireNonNegative(windSpeed, "windSpeed");
		requireNonNegative(descentTime, "descentTime");
		return windSpeed * descentTime;
	}

	/**
	 * Fraction of a hardware rating used by a load, for example 0.6 for 60%.
	 *
	 * @param load   applied load in N
	 * @param rating rated load of the part in N
	 * @return load divided by rating
	 */
	public static double loadFraction(double load, double rating) {
		requireNonNegative(load, "load");
		requirePositive(rating, "rating");
		return load / rating;
	}

	static void requirePositive(double value, String name) {
		if (!(value > 0) || Double.isInfinite(value)) {
			throw new IllegalArgumentException(name + " must be positive and finite, was " + value);
		}
	}

	static void requireNonNegative(double value, String name) {
		if (!(value >= 0) || Double.isInfinite(value)) {
			throw new IllegalArgumentException(name + " must be zero or positive and finite, was " + value);
		}
	}
}
