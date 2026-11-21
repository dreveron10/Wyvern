package info.openrocket.core.wyvern.map;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import info.openrocket.core.simulation.montecarlo.LandingPoint;

/**
 * A launch field: where the pad is and, optionally, the outline of the area the rocket
 * should land in. Coordinates are in degrees.
 */
public class LaunchSite {

	public String name = "";
	public double latitude;
	public double longitude;
	/** Outline of the recovery area as {latitude, longitude} vertices; empty if not drawn. */
	public List<double[]> boundary = new ArrayList<>();

	public LaunchSite() {
	}

	public LaunchSite(String name, double latitude, double longitude) {
		this.name = name;
		this.latitude = latitude;
		this.longitude = longitude;
	}

	/** Whether the boundary has enough vertices to enclose an area. */
	public boolean hasBoundary() {
		return boundary != null && boundary.size() >= 3;
	}

	/**
	 * Whether a position lies inside the boundary. A position exactly on an edge may
	 * count either way.
	 */
	public boolean contains(double lat, double lon) {
		if (!hasBoundary()) {
			return false;
		}
		// Ray casting in a local flat frame around the pad, so longitude is not stretched
		double[] p = GeoMath.toLocal(latitude, longitude, lat, lon);
		boolean inside = false;
		int n = boundary.size();
		double[] previous = GeoMath.toLocal(latitude, longitude, boundary.get(n - 1)[0], boundary.get(n - 1)[1]);
		for (int i = 0; i < n; i++) {
			double[] current = GeoMath.toLocal(latitude, longitude, boundary.get(i)[0], boundary.get(i)[1]);
			boolean crosses = (current[1] > p[1]) != (previous[1] > p[1]);
			if (crosses) {
				double x = current[0] + (p[1] - current[1]) / (previous[1] - current[1]) * (previous[0] - current[0]);
				if (p[0] < x) {
					inside = !inside;
				}
			}
			previous = current;
		}
		return inside;
	}

	/**
	 * Fraction of landing points that fall inside the boundary, with the landing points
	 * given as east/north offsets from a pad position.
	 *
	 * @return a value from 0 to 1, or NaN without a boundary or without points
	 */
	public double fractionInside(double padLatitude, double padLongitude, List<LandingPoint> points) {
		if (!hasBoundary() || points.isEmpty()) {
			return Double.NaN;
		}
		int inside = 0;
		for (LandingPoint point : points) {
			double[] position = GeoMath.offset(padLatitude, padLongitude, point.east(), point.north());
			if (contains(position[0], position[1])) {
				inside++;
			}
		}
		return (double) inside / points.size();
	}

	public String toJson() {
		return new Gson().toJson(this);
	}

	/** Parse one site; returns null if the text is not a valid site. */
	public static LaunchSite fromJson(String json) {
		try {
			return normalize(new Gson().fromJson(json, LaunchSite.class));
		} catch (JsonParseException e) {
			return null;
		}
	}

	public static String listToJson(List<LaunchSite> sites) {
		return new Gson().toJson(sites);
	}

	/** Parse a list of sites; invalid text gives an empty list. */
	public static List<LaunchSite> listFromJson(String json) {
		List<LaunchSite> result = new ArrayList<>();
		if (json == null || json.isBlank()) {
			return result;
		}
		try {
			List<LaunchSite> parsed = new Gson().fromJson(json, new TypeToken<List<LaunchSite>>() {
			}.getType());
			if (parsed != null) {
				for (LaunchSite site : parsed) {
					LaunchSite ok = normalize(site);
					if (ok != null) {
						result.add(ok);
					}
				}
			}
		} catch (JsonParseException e) {
			// Fall through with what was read so far
		}
		return result;
	}

	private static LaunchSite normalize(LaunchSite site) {
		if (site == null || Double.isNaN(site.latitude) || Double.isNaN(site.longitude)
				|| Math.abs(site.latitude) > 90 || Math.abs(site.longitude) > 180) {
			return null;
		}
		if (site.name == null) {
			site.name = "";
		}
		List<double[]> clean = new ArrayList<>();
		if (site.boundary != null) {
			for (double[] vertex : site.boundary) {
				if (vertex != null && vertex.length == 2) {
					clean.add(vertex);
				}
			}
		}
		site.boundary = clean;
		return site;
	}
}
