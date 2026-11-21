package info.openrocket.core.wyvern.recovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;

/**
 * Turns one simulated flight into a {@link RecoveryReport}: descent phases, opening
 * forces, section kinetic energies, hardware margins, ejection charges and pad stay time.
 * <p>
 * The analyzer only reads simulation results. Kinetic energies use the vertical descent
 * speed, which is the convention competition rules are written against.
 */
public final class RecoveryAnalyzer {

	private RecoveryAnalyzer() {
	}

	/**
	 * Analyze a flight.
	 *
	 * @param branch flight data of the body to analyze (normally the sustainer branch)
	 * @param config flight configuration the data was simulated with; may be null for
	 *               data without a design, in which case drag areas and section masses
	 *               from components are unavailable
	 * @param setup  the user's recovery setup
	 * @return the report
	 * @throws IllegalArgumentException if the branch has no time or altitude data
	 */
	public static RecoveryReport analyze(FlightDataBranch branch, FlightConfiguration config, RecoverySetup setup) {
		List<Double> time = branch.get(FlightDataType.TYPE_TIME);
		List<Double> altitude = branch.get(FlightDataType.TYPE_ALTITUDE);
		if (time == null || altitude == null || time.size() < 2) {
			throw new IllegalArgumentException("Flight data has no time and altitude samples");
		}
		int last = time.size() - 1;

		// Apogee: prefer the event, fall back to the highest sample
		double apogeeTime;
		FlightEvent apogeeEvent = branch.getFirstEvent(FlightEvent.Type.APOGEE);
		if (apogeeEvent != null) {
			apogeeTime = apogeeEvent.getTime();
		} else {
			int top = 0;
			for (int i = 1; i <= last; i++) {
				if (altitude.get(i) > altitude.get(top)) {
					top = i;
				}
			}
			apogeeTime = time.get(top);
		}
		double apogeeAltitude = valueAt(branch, FlightDataType.TYPE_ALTITUDE, apogeeTime);

		FlightEvent groundHit = branch.getFirstEvent(FlightEvent.Type.GROUND_HIT);
		boolean reachedGround = groundHit != null;
		double landingTime = reachedGround ? Math.min(groundHit.getTime(), time.get(last)) : time.get(last);
		double landingVelocity = -valueAt(branch, FlightDataType.TYPE_VELOCITY_Z, landingTime);
		double landingDistance = valueAt(branch, FlightDataType.TYPE_POSITION_XY, landingTime);
		double descentMass = valueAt(branch, FlightDataType.TYPE_MASS, landingTime);

		// Deployments in time order
		List<FlightEvent> deployments = new ArrayList<>();
		for (FlightEvent e : branch.getEvents()) {
			if (e.getType() == FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT && e.getTime() <= landingTime) {
				deployments.add(e);
			}
		}
		deployments.sort(Comparator.comparingDouble(FlightEvent::getTime));

		List<RecoveryReport.Phase> phases = new ArrayList<>();
		double totalDragArea = 0;
		double maxOpeningForce = Double.NaN;
		for (int i = 0; i < deployments.size(); i++) {
			FlightEvent e = deployments.get(i);
			double t = e.getTime();
			double endTime = (i + 1 < deployments.size()) ? deployments.get(i + 1).getTime() : landingTime;

			String name = "";
			boolean drogue = false;
			double deviceDragArea = Double.NaN;
			if (e.getSource() instanceof RecoveryDevice device) {
				name = device.getName();
				drogue = device.isDrogue();
				int count = (config != null) ? config.getActiveInstances().count(device) : device.getInstanceCount();
				deviceDragArea = Math.max(count, 1) * device.getCD() * device.getArea();
			} else if (e.getSource() != null) {
				name = e.getSource().getName();
			}
			if (!Double.isNaN(deviceDragArea)) {
				totalDragArea += deviceDragArea;
			}

			double deployVelocity = valueAt(branch, FlightDataType.TYPE_VELOCITY_TOTAL, t);
			double deployDensity = valueAt(branch, FlightDataType.TYPE_AIR_DENSITY, t);
			double openingForce = Double.NaN;
			if (deviceDragArea > 0 && deployDensity > 0) {
				openingForce = RecoveryMath.openingForce(deployDensity, deployVelocity, deviceDragArea,
						setup.openingForceCoeff, setup.forceReductionFactor);
				if (Double.isNaN(maxOpeningForce) || openingForce > maxOpeningForce) {
					maxOpeningForce = openingForce;
				}
			}

			double endVelocity = -valueAt(branch, FlightDataType.TYPE_VELOCITY_Z, endTime);
			double endDensity = valueAt(branch, FlightDataType.TYPE_AIR_DENSITY, endTime);
			double endMass = valueAt(branch, FlightDataType.TYPE_MASS, endTime);
			double terminalVelocity = Double.NaN;
			if (totalDragArea > 0 && endDensity > 0 && endMass > 0) {
				terminalVelocity = RecoveryMath.terminalVelocity(endMass, endDensity, totalDragArea);
			}

			phases.add(new RecoveryReport.Phase(name, drogue, t,
					valueAt(branch, FlightDataType.TYPE_ALTITUDE, t), deployVelocity, deployDensity,
					deviceDragArea, totalDragArea, openingForce, endTime, endVelocity, terminalVelocity));
		}

		// Sections
		List<RecoveryReport.SectionResult> sections = new ArrayList<>();
		double assigned = 0;
		for (RecoverySetup.Section section : setup.sections) {
			double mass = sectionMass(section, config);
			assigned += mass;
			List<Double> energies = new ArrayList<>();
			for (RecoveryReport.Phase phase : phases) {
				energies.add(RecoveryMath.kineticEnergy(mass, phase.endVelocity()));
			}
			sections.add(new RecoveryReport.SectionResult(section.name, mass, List.copyOf(energies)));
		}
		double unassignedMass = descentMass - assigned;

		// Hardware
		double designLoad = maxOpeningForce * setup.safetyFactor;
		List<RecoveryReport.HardwareResult> hardware = new ArrayList<>();
		for (RecoverySetup.HardwareItem item : setup.hardware) {
			double fraction = (item.ratedLoad > 0 && !Double.isNaN(designLoad))
					? RecoveryMath.loadFraction(designLoad, item.ratedLoad)
					: Double.NaN;
			hardware.add(new RecoveryReport.HardwareResult(item.name, item.ratedLoad, fraction));
		}

		return new RecoveryReport(apogeeTime, apogeeAltitude, landingTime, reachedGround,
				landingTime - apogeeTime, landingDistance, landingVelocity, descentMass,
				List.copyOf(phases), List.copyOf(sections), unassignedMass, maxOpeningForce, designLoad,
				List.copyOf(hardware), charges(setup), batteries(setup), padStayTime(setup));
	}

	/**
	 * Ejection charge results for every bay with a usable size and pressure.
	 * Bays that are not filled in yet are reported with NaN values.
	 */
	public static List<RecoveryReport.ChargeResult> charges(RecoverySetup setup) {
		List<RecoveryReport.ChargeResult> results = new ArrayList<>();
		for (RecoverySetup.ChargeBay bay : setup.chargeBays) {
			if (!(bay.diameter > 0 && bay.length > 0 && bay.pressure > 0)) {
				results.add(new RecoveryReport.ChargeResult(bay.name, Double.NaN, Double.NaN, Double.NaN,
						Double.NaN, Double.NaN));
				continue;
			}
			double volume = EjectionCharge.cylinderVolume(bay.diameter, bay.length);
			double primary = EjectionCharge.blackPowderMass(bay.pressure, volume);
			double backup = EjectionCharge.backupMass(primary, Math.max(bay.backupIncrease, 0));
			double force = EjectionCharge.separationForce(bay.pressure, bay.diameter);
			double pinFactor = (bay.shearPinCount > 0 && bay.shearPinStrength > 0)
					? EjectionCharge.shearPinFactor(force, bay.shearPinCount, bay.shearPinStrength)
					: Double.NaN;
			results.add(new RecoveryReport.ChargeResult(bay.name, volume, primary, backup, force, pinFactor));
		}
		return List.copyOf(results);
	}

	/** Endurance of every battery; NaN for batteries that are not filled in yet. */
	public static List<RecoveryReport.BatteryResult> batteries(RecoverySetup setup) {
		List<RecoveryReport.BatteryResult> results = new ArrayList<>();
		for (RecoverySetup.Battery battery : setup.batteries) {
			results.add(new RecoveryReport.BatteryResult(battery.name, endurance(battery)));
		}
		return List.copyOf(results);
	}

	/** Shortest battery endurance in s, or NaN if no battery is filled in. */
	public static double padStayTime(RecoverySetup setup) {
		double shortest = Double.NaN;
		for (RecoverySetup.Battery battery : setup.batteries) {
			double endurance = endurance(battery);
			if (!Double.isNaN(endurance) && (Double.isNaN(shortest) || endurance < shortest)) {
				shortest = endurance;
			}
		}
		return shortest;
	}

	private static double endurance(RecoverySetup.Battery battery) {
		if (!(battery.capacity > 0 && battery.current > 0 && battery.usableFraction > 0)) {
			return Double.NaN;
		}
		return battery.capacity * Math.min(battery.usableFraction, 1) / battery.current * 3600;
	}

	/**
	 * Mass of a component with everything inside it, without motors.
	 * <p>
	 * If a component above it (typically the stage) has its mass overridden together
	 * with its subcomponents, the individual masses below that override are unknown.
	 * They are then scaled in proportion so that they add up to the override, which
	 * keeps the section masses consistent with the mass the simulation flies.
	 *
	 * @param component the component
	 * @return mass in kg
	 */
	public static double componentMass(RocketComponent component) {
		double mass = component.getSectionMass();
		for (RocketComponent a = component.getParent(); a != null; a = a.getParent()) {
			if (a.isMassOverridden() && a.isSubcomponentsOverriddenMass()) {
				double modeled = a.getComponentMass();
				for (RocketComponent child : a.getChildren()) {
					modeled += child.getSectionMass();
				}
				if (modeled > 0) {
					mass *= a.getOverrideMass() / modeled;
				}
			}
		}
		return mass;
	}

	/**
	 * Mass of a section: its components with everything inside them, the burnt-out
	 * motors mounted inside those components, and the section's extra mass.
	 *
	 * @param section the section
	 * @param config  the flight configuration, or null to count only the extra mass
	 * @return mass in kg
	 */
	public static double sectionMass(RecoverySetup.Section section, FlightConfiguration config) {
		double mass = Math.max(section.extraMass, 0);
		if (config == null) {
			return mass;
		}
		Rocket rocket = config.getRocket();
		for (String id : section.componentIds) {
			RocketComponent component;
			try {
				component = rocket.findComponent(UUID.fromString(id));
			} catch (IllegalArgumentException e) {
				continue;
			}
			if (component != null) {
				mass += componentMass(component);
			}
		}
		for (MotorConfiguration motor : config.getActiveMotors()) {
			if (motor.getMotor() == null || !(motor.getMount() instanceof RocketComponent mount)) {
				continue;
			}
			for (RocketComponent c = mount; c != null; c = c.getParent()) {
				if (section.contains(c.getID())) {
					mass += motor.getMotor().getBurnoutMass() * motor.getMotorCount();
					break;
				}
			}
		}
		return mass;
	}

	/**
	 * Linearly interpolated value of a data type at a time. Times outside the data are
	 * clamped to the first or last sample.
	 *
	 * @return the value, or NaN if the branch does not hold the type
	 */
	static double valueAt(FlightDataBranch branch, FlightDataType type, double t) {
		List<Double> time = branch.get(FlightDataType.TYPE_TIME);
		List<Double> values = branch.get(type);
		if (time == null || values == null || values.isEmpty()) {
			return Double.NaN;
		}
		int n = Math.min(time.size(), values.size());
		if (t <= time.get(0)) {
			return values.get(0);
		}
		if (t >= time.get(n - 1)) {
			return values.get(n - 1);
		}
		int lo = 0;
		int hi = n - 1;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (time.get(mid) <= t) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		double t0 = time.get(lo);
		double t1 = time.get(hi);
		if (t1 <= t0) {
			return values.get(hi);
		}
		double f = (t - t0) / (t1 - t0);
		return values.get(lo) + f * (values.get(hi) - values.get(lo));
	}
}
