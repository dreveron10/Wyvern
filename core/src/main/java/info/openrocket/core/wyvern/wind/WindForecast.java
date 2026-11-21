package info.openrocket.core.wyvern.wind;

import java.time.LocalDateTime;
import java.util.List;

import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.models.wind.WindModel;
import info.openrocket.core.models.wind.WindModelType;
import info.openrocket.core.simulation.SimulationOptions;

/**
 * An hourly wind forecast for one place: for every hour, the wind at a set of heights
 * above the ground.
 *
 * @param latitude         latitude of the forecast grid point in degrees
 * @param longitude        longitude of the forecast grid point in degrees
 * @param elevation        ground elevation used by the forecast in m above sea level
 * @param timezone         name of the time zone the hours are given in
 * @param hours            the forecast hours in order
 */
public record WindForecast(double latitude, double longitude, double elevation, String timezone, List<Hour> hours) {

	/**
	 * Wind at one height.
	 *
	 * @param altitude  height above the ground in m
	 * @param speed     mean wind speed in m/s
	 * @param direction direction the wind blows from in rad, clockwise from north
	 */
	public record Level(double altitude, double speed, double direction) {
	}

	/**
	 * One forecast hour.
	 *
	 * @param time   start of the hour in the forecast's time zone
	 * @param gust   surface gust speed in m/s, or NaN if the forecast has none
	 * @param levels wind levels sorted by height, lowest first
	 */
	public record Hour(LocalDateTime time, double gust, List<Level> levels) {
	}

	/** The forecast hour closest to a time, or null if the forecast is empty. */
	public Hour closest(LocalDateTime time) {
		Hour best = null;
		long bestDistance = Long.MAX_VALUE;
		for (Hour hour : hours) {
			long distance = Math.abs(java.time.Duration.between(time, hour.time()).toMinutes());
			if (distance < bestDistance) {
				bestDistance = distance;
				best = hour;
			}
		}
		return best;
	}

	/**
	 * Use a forecast hour as the wind of a simulation: switches the simulation to the
	 * multi-level wind model, with heights measured from the launch site, and replaces its
	 * levels with the forecast's.
	 *
	 * @param hour                the forecast hour to apply
	 * @param options             the simulation options to change
	 * @param turbulenceIntensity standard deviation of the wind speed as a fraction of the
	 *                            mean, applied at every level; the forecast itself gives
	 *                            hourly means only
	 */
	public static void apply(Hour hour, SimulationOptions options, double turbulenceIntensity) {
		if (hour.levels().isEmpty()) {
			throw new IllegalArgumentException("The forecast hour has no wind levels");
		}
		MultiLevelPinkNoiseWindModel model = options.getMultiLevelWindModel();
		model.clearLevels();
		model.setAltitudeReference(WindModel.AltitudeReference.AGL);
		for (Level level : hour.levels()) {
			model.addWindLevel(level.altitude(), level.speed(), level.direction(),
					Math.max(0, turbulenceIntensity) * level.speed());
		}
		options.setWindModelType(WindModelType.MULTI_LEVEL);
	}
}
