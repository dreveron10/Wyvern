package info.openrocket.core.wyvern.wind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel.LevelWindModel;
import info.openrocket.core.models.wind.WindModel;
import info.openrocket.core.models.wind.WindModelType;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.wyvern.wind.WindForecast.Hour;

/**
 * The fixture has the shape of an Open-Meteo answer, with made-up values: ground at
 * 100 m, two hours, and three pressure levels at 150, 600 and 1500 m above sea level.
 */
public class OpenMeteoTest extends BaseTestCase {

	private static final String FIXTURE = "{\"latitude\":18.2,\"longitude\":-67.1,\"elevation\":100.0,"
			+ "\"utc_offset_seconds\":-14400,\"timezone\":\"America/Puerto_Rico\","
			+ "\"hourly\":{\"time\":[\"2026-11-21T09:00\",\"2026-11-21T10:00\"],"
			+ "\"wind_gusts_10m\":[6.0,null],"
			+ "\"wind_speed_10m\":[3.0,4.0],\"wind_direction_10m\":[90,180],"
			+ "\"wind_speed_80m\":[5.0,6.0],\"wind_direction_80m\":[100,190],"
			+ "\"wind_speed_120m\":[5.5,null],\"wind_direction_120m\":[105,195],"
			+ "\"wind_speed_180m\":[6.0,7.0],\"wind_direction_180m\":[110,200],"
			+ "\"wind_speed_1000hPa\":[4.0,4.0],\"wind_direction_1000hPa\":[95,95],"
			+ "\"geopotential_height_1000hPa\":[150.0,150.0],"
			+ "\"wind_speed_950hPa\":[8.0,9.0],\"wind_direction_950hPa\":[120,210],"
			+ "\"geopotential_height_950hPa\":[600.0,610.0],"
			+ "\"wind_speed_850hPa\":[10.0,11.0],\"wind_direction_850hPa\":[270,280],"
			+ "\"geopotential_height_850hPa\":[1500.0,1510.0]}}";

	@Test
	public void testRequestUrl() {
		String url = OpenMeteo.requestUrl(18.2112, -67.1396, 3);
		assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=18.21120&longitude=-67.13960&"));
		assertTrue(url.contains("wind_speed_10m,wind_direction_10m"));
		assertTrue(url.contains("wind_speed_850hPa,wind_direction_850hPa,geopotential_height_850hPa"));
		assertTrue(url.contains("wind_speed_unit=ms"));
		assertTrue(url.endsWith("forecast_days=3"));
	}

	@Test
	public void testParse() {
		WindForecast forecast = OpenMeteo.parse(new StringReader(FIXTURE));
		assertEquals(100.0, forecast.elevation(), 1e-9);
		assertEquals("America/Puerto_Rico", forecast.timezone());
		assertEquals(2, forecast.hours().size());

		Hour first = forecast.hours().get(0);
		assertEquals(LocalDateTime.of(2026, 11, 21, 9, 0), first.time());
		assertEquals(6.0, first.gust(), 1e-9);
		// Four fixed heights, then the pressure levels above 180 m: 600 - 100 and 1500 - 100.
		// The 1000 hPa level is 50 m above the ground, inside the fixed heights, and is dropped.
		assertEquals(6, first.levels().size());
		assertEquals(10, first.levels().get(0).altitude(), 1e-9);
		assertEquals(3.0, first.levels().get(0).speed(), 1e-9);
		assertEquals(Math.PI / 2, first.levels().get(0).direction(), 1e-9);
		assertEquals(500, first.levels().get(4).altitude(), 1e-9);
		assertEquals(8.0, first.levels().get(4).speed(), 1e-9);
		assertEquals(1400, first.levels().get(5).altitude(), 1e-9);
		assertEquals(Math.toRadians(270), first.levels().get(5).direction(), 1e-9);

		// Second hour: the 120 m speed and the gust are null
		Hour second = forecast.hours().get(1);
		assertTrue(Double.isNaN(second.gust()));
		assertEquals(5, second.levels().size());
		assertEquals(180, second.levels().get(2).altitude(), 1e-9);
		assertEquals(510, second.levels().get(3).altitude(), 1e-9);
	}

	@Test
	public void testClosestHour() {
		WindForecast forecast = OpenMeteo.parse(new StringReader(FIXTURE));
		assertEquals(9, forecast.closest(LocalDateTime.of(2026, 11, 21, 9, 20)).time().getHour());
		assertEquals(10, forecast.closest(LocalDateTime.of(2026, 11, 21, 9, 40)).time().getHour());
		assertEquals(10, forecast.closest(LocalDateTime.of(2026, 11, 22, 0, 0)).time().getHour());
	}

	@Test
	public void testErrors() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> OpenMeteo
				.parse(new StringReader("{\"error\":true,\"reason\":\"Latitude must be in range of -90 to 90\"}")));
		assertEquals("Latitude must be in range of -90 to 90", e.getMessage());
		assertThrows(IllegalArgumentException.class, () -> OpenMeteo.parse(new StringReader("<html>")));
		assertThrows(IllegalArgumentException.class, () -> OpenMeteo.parse(new StringReader("{\"latitude\":1}")));
		assertThrows(IllegalArgumentException.class,
				() -> OpenMeteo.parse(new StringReader("{\"hourly\":{\"time\":[\"2026-11-21T09:00\"]}}")));
	}

	@Test
	public void testApplyToSimulation() {
		WindForecast forecast = OpenMeteo.parse(new StringReader(FIXTURE));
		SimulationOptions options = new SimulationOptions();
		WindForecast.apply(forecast.hours().get(0), options, 0.1);

		assertEquals(WindModelType.MULTI_LEVEL, options.getWindModelType());
		assertEquals(WindModel.AltitudeReference.AGL, options.getMultiLevelWindModel().getAltitudeReference());
		List<LevelWindModel> levels = options.getMultiLevelWindModel().getLevels();
		assertEquals(6, levels.size());
		assertEquals(10, levels.get(0).getAltitude(), 1e-9);
		assertEquals(3.0, levels.get(0).getSpeed(), 1e-9);
		assertEquals(Math.PI / 2, levels.get(0).getDirection(), 1e-9);
		assertEquals(0.3, levels.get(0).getStandardDeviation(), 1e-9);
		assertEquals(1400, levels.get(5).getAltitude(), 1e-9);
		assertEquals(1.0, levels.get(5).getStandardDeviation(), 1e-9);

		// Wind from the east at 10 m: the model's velocity vector points east, which the
		// simulation adds to the rocket's velocity to get airspeed
		WindForecast.apply(forecast.hours().get(0), options, 0);
		assertEquals(3.0, options.getMultiLevelWindModel().getWindVelocity(0, 10).getX(), 1e-9);
		assertEquals(0.0, options.getMultiLevelWindModel().getWindVelocity(0, 10).getY(), 1e-9);
	}
}
