package info.openrocket.core.wyvern.wind;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import info.openrocket.core.wyvern.wind.WindForecast.Hour;
import info.openrocket.core.wyvern.wind.WindForecast.Level;

/**
 * Wind forecasts from the Open-Meteo forecast API (https://open-meteo.com).
 * <p>
 * Near the ground the API gives wind at fixed heights above the ground (10, 80, 120 and
 * 180 m). Higher up it gives wind on pressure levels together with the height of each
 * level above sea level; subtracting the ground elevation turns those into heights above
 * the launch site. Pressure levels that fall below the highest fixed height are dropped,
 * so the two sets never overlap.
 */
public final class OpenMeteo {

	/** Base URL of the API; the system property lets a test or a self-hosted instance replace it. */
	public static final String URL_PROPERTY = "wyvern.wind.forecastUrl";
	private static final String DEFAULT_URL = "https://api.open-meteo.com/v1/forecast";

	private static final int[] HEIGHTS = { 10, 80, 120, 180 };
	private static final int[] PRESSURE_LEVELS = { 1000, 975, 950, 925, 900, 850, 800, 700, 600, 500, 400, 300 };

	private OpenMeteo() {
	}

	/**
	 * The request for an hourly wind forecast.
	 *
	 * @param latitude  latitude in degrees, north positive
	 * @param longitude longitude in degrees, east positive
	 * @param days      number of days to forecast, 1 to 16
	 */
	public static String requestUrl(double latitude, double longitude, int days) {
		StringBuilder hourly = new StringBuilder("wind_gusts_10m");
		for (int h : HEIGHTS) {
			hourly.append(",wind_speed_").append(h).append("m,wind_direction_").append(h).append('m');
		}
		for (int p : PRESSURE_LEVELS) {
			hourly.append(",wind_speed_").append(p).append("hPa,wind_direction_").append(p)
					.append("hPa,geopotential_height_").append(p).append("hPa");
		}
		return String.format(Locale.ROOT,
				"%s?latitude=%.5f&longitude=%.5f&hourly=%s&wind_speed_unit=ms&timezone=auto&forecast_days=%d",
				System.getProperty(URL_PROPERTY, DEFAULT_URL), latitude, longitude, hourly,
				Math.max(1, Math.min(16, days)));
	}

	/**
	 * Download a forecast.
	 *
	 * @throws IOException if the service cannot be reached or answers with an error
	 */
	public static WindForecast fetch(double latitude, double longitude, int days) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) URI.create(requestUrl(latitude, longitude, days)).toURL()
				.openConnection();
		connection.setConnectTimeout(10000);
		connection.setReadTimeout(20000);
		connection.setRequestProperty("User-Agent", "Wyvern (OpenRocket fork)");
		int status = connection.getResponseCode();
		InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
		if (stream == null) {
			throw new IOException("The forecast service answered with HTTP " + status);
		}
		try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			return parse(reader);
		} catch (IllegalArgumentException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Read a forecast from the API's JSON answer.
	 *
	 * @throws IllegalArgumentException if the answer is an error message or not a forecast
	 */
	public static WindForecast parse(Reader json) {
		JsonObject root;
		try {
			JsonElement element = JsonParser.parseReader(json);
			if (!element.isJsonObject()) {
				throw new IllegalArgumentException("The forecast service did not return a forecast");
			}
			root = element.getAsJsonObject();
		} catch (JsonParseException e) {
			throw new IllegalArgumentException("The forecast service did not return a forecast", e);
		}
		if (root.has("error") && root.has("reason")) {
			throw new IllegalArgumentException(root.get("reason").getAsString());
		}
		if (!root.has("hourly") || !root.getAsJsonObject("hourly").has("time")) {
			throw new IllegalArgumentException("The forecast holds no hourly data");
		}
		JsonObject hourly = root.getAsJsonObject("hourly");
		double elevation = number(root, "elevation");
		if (Double.isNaN(elevation)) {
			elevation = 0;
		}
		JsonArray times = hourly.getAsJsonArray("time");
		List<Hour> hours = new ArrayList<>();
		for (int i = 0; i < times.size(); i++) {
			LocalDateTime time;
			try {
				time = LocalDateTime.parse(times.get(i).getAsString());
			} catch (DateTimeParseException e) {
				continue;
			}
			List<Level> levels = new ArrayList<>();
			for (int h : HEIGHTS) {
				addLevel(levels, h, value(hourly, "wind_speed_" + h + "m", i),
						value(hourly, "wind_direction_" + h + "m", i));
			}
			double top = levels.isEmpty() ? 0 : levels.get(levels.size() - 1).altitude();
			for (int p : PRESSURE_LEVELS) {
				double height = value(hourly, "geopotential_height_" + p + "hPa", i) - elevation;
				if (height > top) {
					addLevel(levels, height, value(hourly, "wind_speed_" + p + "hPa", i),
							value(hourly, "wind_direction_" + p + "hPa", i));
				}
			}
			levels.sort(Comparator.comparingDouble(Level::altitude));
			if (!levels.isEmpty()) {
				hours.add(new Hour(time, value(hourly, "wind_gusts_10m", i), List.copyOf(levels)));
			}
		}
		if (hours.isEmpty()) {
			throw new IllegalArgumentException("The forecast holds no wind data");
		}
		String timezone = root.has("timezone") && !root.get("timezone").isJsonNull() ? root.get("timezone").getAsString()
				: "GMT";
		return new WindForecast(number(root, "latitude"), number(root, "longitude"), elevation, timezone,
				List.copyOf(hours));
	}

	private static void addLevel(List<Level> levels, double altitude, double speed, double directionDegrees) {
		if (Double.isNaN(altitude) || Double.isNaN(speed) || Double.isNaN(directionDegrees)) {
			return;
		}
		for (Level existing : levels) {
			if (existing.altitude() == altitude) {
				return;
			}
		}
		levels.add(new Level(altitude, speed, Math.toRadians(directionDegrees)));
	}

	private static double number(JsonObject object, String key) {
		JsonElement e = object.get(key);
		return e == null || e.isJsonNull() ? Double.NaN : e.getAsDouble();
	}

	/** Entry i of an hourly array, or NaN if the variable or the value is missing. */
	private static double value(JsonObject hourly, String key, int i) {
		JsonElement array = hourly.get(key);
		if (array == null || !array.isJsonArray() || i >= array.getAsJsonArray().size()) {
			return Double.NaN;
		}
		JsonElement e = array.getAsJsonArray().get(i);
		return e.isJsonNull() ? Double.NaN : e.getAsDouble();
	}
}
