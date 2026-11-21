package info.openrocket.core.wyvern.flightlog;

import java.util.ArrayList;
import java.util.List;

import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.wyvern.flightlog.FlightLog.Event;
import info.openrocket.core.wyvern.flightlog.FlightLog.EventType;

/**
 * Turns a simulated flight into a {@link FlightLog}, so a simulation and a recorded
 * flight go through the same statistics and plotting code.
 */
public final class SimulationLog {

	private SimulationLog() {
	}

	/**
	 * @param branch a simulated flight branch with time, altitude and vertical velocity
	 * @return the branch as a flight log; the first recovery deployment is the drogue and
	 *         the second, if there is one, the main
	 * @throws IllegalArgumentException if the branch holds fewer than two samples
	 */
	public static FlightLog of(FlightDataBranch branch) {
		List<Double> t = branch.get(FlightDataType.TYPE_TIME);
		List<Double> h = branch.get(FlightDataType.TYPE_ALTITUDE);
		List<Double> v = branch.get(FlightDataType.TYPE_VELOCITY_Z);
		if (t == null || h == null || t.size() < 2) {
			throw new IllegalArgumentException("The simulation has no flight data");
		}
		int n = t.size();
		double[] time = new double[n];
		double[] altitude = new double[n];
		double[] velocity = v == null ? null : new double[n];
		for (int i = 0; i < n; i++) {
			time[i] = t.get(i);
			altitude[i] = h.get(i);
			if (velocity != null) {
				velocity[i] = v.get(i);
			}
		}
		List<Event> events = new ArrayList<>();
		int deployments = 0;
		for (FlightEvent event : branch.getEvents()) {
			switch (event.getType()) {
			case LIFTOFF:
				events.add(new Event(EventType.LIFTOFF, event.getTime()));
				break;
			case BURNOUT:
				events.add(new Event(EventType.BURNOUT, event.getTime()));
				break;
			case APOGEE:
				events.add(new Event(EventType.APOGEE, event.getTime()));
				break;
			case RECOVERY_DEVICE_DEPLOYMENT:
				deployments++;
				if (deployments == 1) {
					events.add(new Event(EventType.DROGUE, event.getTime()));
				} else if (deployments == 2) {
					events.add(new Event(EventType.MAIN, event.getTime()));
				}
				break;
			default:
				break;
			}
		}
		return new FlightLog("Simulation", time, altitude, velocity, events, List.of());
	}
}
