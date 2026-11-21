package info.openrocket.core.wyvern.calibration;

import java.util.Locale;

import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.simulation.SimulationConditions;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.extension.AbstractSimulationExtension;
import info.openrocket.core.simulation.listeners.AbstractSimulationListener;

/**
 * Simulation extension that multiplies the rocket's computed drag coefficient by a
 * constant during the ascent, to bring a simulation in line with a measured apogee.
 * <p>
 * Only the flight up to apogee is affected; descent under parachutes is governed by the
 * parachutes' own drag coefficients.
 */
public class DragScale extends AbstractSimulationExtension {

	public static final double MIN_SCALE = 0.2;
	public static final double MAX_SCALE = 5.0;

	@Override
	public boolean isMonteCarloSafe() {
		return true;
	}

	@Override
	public void initialize(SimulationConditions conditions) throws SimulationException {
		conditions.getSimulationListenerList().add(new Listener(getScale()));
	}

	@Override
	public String getName() {
		return trans.get("Wyvern.calibration.dragScale.name") + String.format(Locale.ROOT, " \u00D7%.3f", getScale());
	}

	@Override
	public String getDescription() {
		return trans.get("Wyvern.calibration.dragScale.desc");
	}

	public double getScale() {
		return config.getDouble("scale", 1.0);
	}

	public void setScale(double scale) {
		config.put("scale", Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale)));
		fireChangeEvent();
	}

	/** Scales the drag coefficients the aerodynamic calculator produced, until apogee. */
	static class Listener extends AbstractSimulationListener {
		private final double scale;

		Listener(double scale) {
			this.scale = scale;
		}

		@Override
		public AerodynamicForces postAerodynamicCalculation(SimulationStatus status, AerodynamicForces forces)
				throws SimulationException {
			if (forces == null || status.isApogeeReached()) {
				return null;
			}
			forces.setCD(forces.getCD() * scale);
			forces.setCDaxial(forces.getCDaxial() * scale);
			forces.setFrictionCD(forces.getFrictionCD() * scale);
			forces.setPressureCD(forces.getPressureCD() * scale);
			forces.setBaseCD(forces.getBaseCD() * scale);
			return forces;
		}
	}
}
