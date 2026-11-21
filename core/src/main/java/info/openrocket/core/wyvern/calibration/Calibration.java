package info.openrocket.core.wyvern.calibration;

import java.util.function.DoubleUnaryOperator;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.extension.SimulationExtension;

/**
 * Works out the corrections that make a simulation agree with a recorded flight: a
 * parachute drag coefficient from a measured descent rate, and a drag scale from a
 * measured apogee.
 */
public final class Calibration {

	private Calibration() {
	}

	/**
	 * Drag coefficient a parachute needs for the simulated descent rate to equal the
	 * measured one.
	 * <p>
	 * At a steady descent rate v the weight equals the drag, so the total drag area of
	 * everything that is open is proportional to 1 / v&sup2;. The drag area needed is
	 * therefore the simulated total times (simulated rate / measured rate)&sup2;, and
	 * whatever the other open parachutes do not supply has to come from this one.
	 *
	 * @param area          reference area of the parachute being calibrated in m&sup2;
	 * @param simulatedCdA  total drag area (sum of Cd times area) of all parachutes open in
	 *                      this phase of the simulation, in m&sup2;
	 * @param otherCdA      drag area in m&sup2; the other open parachutes will have once
	 *                      calibrated
	 * @param simulatedRate descent rate of the simulation in this phase in m/s
	 * @param measuredRate  descent rate measured in this phase in m/s
	 * @return the drag coefficient, or NaN if the inputs do not allow one (missing data,
	 *         or the other parachutes alone already give more drag than was measured)
	 */
	public static double parachuteCd(double area, double simulatedCdA, double otherCdA, double simulatedRate,
			double measuredRate) {
		if (!(area > 0) || !(simulatedCdA > 0) || !(simulatedRate > 0) || !(measuredRate > 0) || !(otherCdA >= 0)) {
			return Double.NaN;
		}
		double ratio = simulatedRate / measuredRate;
		double needed = simulatedCdA * ratio * ratio - otherCdA;
		return needed > 0 ? needed / area : Double.NaN;
	}

	/**
	 * Find the drag scale at which a flight reaches a target apogee, by bisection.
	 *
	 * @param apogeeForScale apogee in m as a function of the drag scale; must fall as the
	 *                       scale rises
	 * @param targetApogee   apogee to match in m
	 * @param tolerance      acceptable apogee error in m
	 * @return the scale, or NaN if the target cannot be reached between
	 *         {@link DragScale#MIN_SCALE} and {@link DragScale#MAX_SCALE}
	 */
	public static double solveDragScale(DoubleUnaryOperator apogeeForScale, double targetApogee, double tolerance) {
		double lo = DragScale.MIN_SCALE;
		double hi = DragScale.MAX_SCALE;
		double atOne = apogeeForScale.applyAsDouble(1.0);
		if (Math.abs(atOne - targetApogee) <= tolerance) {
			return 1.0;
		}
		// Less drag flies higher: narrow the bracket from the unscaled flight
		if (atOne > targetApogee) {
			lo = 1.0;
			if (apogeeForScale.applyAsDouble(hi) > targetApogee) {
				return Double.NaN;
			}
		} else {
			hi = 1.0;
			if (apogeeForScale.applyAsDouble(lo) < targetApogee) {
				return Double.NaN;
			}
		}
		for (int i = 0; i < 40; i++) {
			double mid = 0.5 * (lo + hi);
			double apogee = apogeeForScale.applyAsDouble(mid);
			if (Math.abs(apogee - targetApogee) <= tolerance) {
				return mid;
			}
			if (apogee > targetApogee) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return 0.5 * (lo + hi);
	}

	/**
	 * Find the drag scale that makes a simulation reach a measured apogee. The simulation
	 * itself is not changed: a copy is flown repeatedly. Any drag scale the simulation
	 * already has is replaced in the copy, so the result is the scale to set, not a
	 * factor on top of the current one.
	 *
	 * @param simulation   the simulation to calibrate
	 * @param targetApogee measured apogee in m above the launch site
	 * @return the drag scale, or NaN if no scale in the allowed range reaches the apogee
	 * @throws SimulationException if the simulation cannot be run
	 */
	public static double dragScaleForApogee(Simulation simulation, double targetApogee) throws SimulationException {
		Simulation copy = simulation.copy();
		copy.getSimulationExtensions().removeIf(e -> e instanceof DragScale);
		DragScale extension = new DragScale();
		copy.getSimulationExtensions().add(extension);
		SimulationException[] failure = new SimulationException[1];
		double scale = solveDragScale(k -> {
			if (failure[0] != null) {
				return Double.NaN;
			}
			extension.setScale(k);
			try {
				copy.simulate();
				return copy.getSimulatedData().getMaxAltitude();
			} catch (SimulationException e) {
				failure[0] = e;
				return Double.NaN;
			}
		}, targetApogee, Math.max(0.1, 0.0005 * targetApogee));
		if (failure[0] != null) {
			throw failure[0];
		}
		return scale;
	}

	/** The drag scale extension of a simulation, or null if it has none. */
	public static DragScale dragScaleOf(Simulation simulation) {
		for (SimulationExtension extension : simulation.getSimulationExtensions()) {
			if (extension instanceof DragScale scale) {
				return scale;
			}
		}
		return null;
	}

	/** Set the drag scale of a simulation, adding the extension if it is not there yet. */
	public static void setDragScale(Simulation simulation, double scale) {
		DragScale extension = dragScaleOf(simulation);
		if (extension == null) {
			extension = new DragScale();
			simulation.getSimulationExtensions().add(extension);
		}
		extension.setScale(scale);
	}
}
