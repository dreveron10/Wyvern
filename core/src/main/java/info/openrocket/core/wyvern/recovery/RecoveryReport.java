package info.openrocket.core.wyvern.recovery;

import java.util.List;

/**
 * Result of a recovery analysis of one simulated flight. All values are in SI units.
 * A value that cannot be computed from the available data is NaN.
 *
 * @param apogeeTime        time of apogee in s
 * @param apogeeAltitude    altitude at apogee in m
 * @param landingTime       time of ground hit (or of the last data point) in s
 * @param reachedGround     whether the flight data contains a ground hit
 * @param descentTime       time from apogee to landing in s
 * @param landingDistance   lateral distance from the launch site at landing in m
 * @param landingVelocity   vertical speed at landing in m/s (positive down)
 * @param descentMass       rocket mass at landing in kg
 * @param phases            descent phases, one per recovery device deployment, in time order
 * @param sections          section masses, with kinetic energies matching {@code phases}
 * @param unassignedMass    descent mass not covered by any section, in kg (negative if the
 *                          sections add up to more than the rocket)
 * @param maxOpeningForce   largest opening force over all deployments in N
 * @param designLoad        {@code maxOpeningForce} times the safety factor in N
 * @param hardware          hardware ratings compared with the design load
 * @param charges           ejection charge sizing per bay
 * @param batteries         pad stay time per battery
 * @param padStayTime       shortest battery endurance in s
 */
public record RecoveryReport(
		double apogeeTime,
		double apogeeAltitude,
		double landingTime,
		boolean reachedGround,
		double descentTime,
		double landingDistance,
		double landingVelocity,
		double descentMass,
		List<Phase> phases,
		List<SectionResult> sections,
		double unassignedMass,
		double maxOpeningForce,
		double designLoad,
		List<HardwareResult> hardware,
		List<ChargeResult> charges,
		List<BatteryResult> batteries,
		double padStayTime) {

	/**
	 * The part of the descent that follows one recovery device deployment.
	 *
	 * @param deviceName          name of the device that deployed
	 * @param drogue              whether the device is marked as a drogue
	 * @param deployTime          deployment time in s
	 * @param deployAltitude      altitude at deployment in m
	 * @param deployVelocity      total airspeed at deployment in m/s
	 * @param deployAirDensity    air density at deployment in kg/m^3
	 * @param deviceDragArea      Cd times area of this device (all instances) in m^2
	 * @param totalDragArea       Cd times area of every device deployed so far in m^2
	 * @param openingForce        opening force of this device in N
	 * @param endTime             time the phase ends (next deployment or landing) in s
	 * @param endVelocity         simulated vertical speed at the end of the phase in m/s (positive down)
	 * @param terminalVelocity    closed-form terminal velocity at the end of the phase in m/s
	 */
	public record Phase(
			String deviceName,
			boolean drogue,
			double deployTime,
			double deployAltitude,
			double deployVelocity,
			double deployAirDensity,
			double deviceDragArea,
			double totalDragArea,
			double openingForce,
			double endTime,
			double endVelocity,
			double terminalVelocity) {

		public double duration() {
			return endTime - deployTime;
		}
	}

	/**
	 * Mass and kinetic energy of one section.
	 *
	 * @param name            section name
	 * @param mass            section mass in kg, including burnt-out motors and extra mass
	 * @param kineticEnergies kinetic energy in J at the end of each phase, in the order of
	 *                        {@link RecoveryReport#phases()}; the last entry is the landing energy
	 */
	public record SectionResult(String name, double mass, List<Double> kineticEnergies) {

		/** Kinetic energy at landing in J, or NaN if there are no phases. */
		public double landingEnergy() {
			return kineticEnergies.isEmpty() ? Double.NaN : kineticEnergies.get(kineticEnergies.size() - 1);
		}
	}

	/**
	 * @param name         hardware name
	 * @param ratedLoad    rated load in N
	 * @param loadFraction design load divided by the rated load
	 */
	public record HardwareResult(String name, double ratedLoad, double loadFraction) {
	}

	/**
	 * @param name            bay name
	 * @param volume          bay volume in m^3
	 * @param primaryMass     primary black powder charge in kg
	 * @param backupMass      backup black powder charge in kg
	 * @param separationForce force from the primary charge in N
	 * @param shearPinFactor  separation force over total shear pin strength, or NaN without pins
	 */
	public record ChargeResult(String name, double volume, double primaryMass, double backupMass,
			double separationForce, double shearPinFactor) {
	}

	/**
	 * @param name      battery name
	 * @param endurance time the battery lasts at the stated draw, in s
	 */
	public record BatteryResult(String name, double endurance) {
	}
}
