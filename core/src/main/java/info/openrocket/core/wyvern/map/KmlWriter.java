package info.openrocket.core.wyvern.map;

import java.util.List;
import java.util.Locale;

import info.openrocket.core.simulation.montecarlo.DispersionStatistics;
import info.openrocket.core.simulation.montecarlo.LandingBody;
import info.openrocket.core.simulation.montecarlo.LandingPoint;
import info.openrocket.core.simulation.montecarlo.MonteCarloResult;

/**
 * Writes a Monte Carlo landing dispersion as KML for Google Earth and similar tools:
 * the launch pad, every landing point per body, and the 1, 2 and 3 sigma ellipses.
 */
public final class KmlWriter {

	private static final int ELLIPSE_SEGMENTS = 72;

	private KmlWriter() {
	}

	/**
	 * @param name         document name
	 * @param padLatitude  launch site latitude in degrees
	 * @param padLongitude launch site longitude in degrees
	 * @param result       the Monte Carlo result
	 * @return the KML document
	 */
	public static String write(String name, double padLatitude, double padLongitude, MonteCarloResult result) {
		StringBuilder kml = new StringBuilder();
		kml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		kml.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n");
		kml.append("<name>").append(escape(name)).append("</name>\n");
		kml.append("<Placemark><name>Launch pad</name><Point><coordinates>")
				.append(coordinate(padLatitude, padLongitude)).append("</coordinates></Point></Placemark>\n");

		for (LandingBody body : result.getLandingBodies()) {
			List<LandingPoint> points = result.getLandingPoints(body.bodyId());
			if (points.isEmpty()) {
				continue;
			}
			kml.append("<Folder><name>").append(escape(body.branchName())).append("</name>\n");
			if (points.size() >= 2) {
				DispersionStatistics stats = DispersionStatistics.from(points);
				for (int sigma = 1; sigma <= 3; sigma++) {
					kml.append("<Placemark><name>").append(sigma).append(" sigma</name><LineString><coordinates>");
					for (double[] p : ellipse(stats.getEllipse(sigma), padLatitude, padLongitude)) {
						kml.append(coordinate(p[0], p[1])).append(' ');
					}
					kml.append("</coordinates></LineString></Placemark>\n");
				}
			}
			kml.append("<Folder><name>Landings</name>\n");
			int run = 1;
			for (LandingPoint point : points) {
				double[] p = GeoMath.offset(padLatitude, padLongitude, point.east(), point.north());
				kml.append("<Placemark><name>").append(run++).append("</name><Point><coordinates>")
						.append(coordinate(p[0], p[1])).append("</coordinates></Point></Placemark>\n");
			}
			kml.append("</Folder>\n</Folder>\n");
		}
		kml.append("</Document>\n</kml>\n");
		return kml.toString();
	}

	/**
	 * Outline of a dispersion ellipse as a closed ring of {latitude, longitude} points.
	 */
	public static double[][] ellipse(DispersionStatistics.DispersionEllipse ellipse, double padLatitude,
			double padLongitude) {
		double[][] ring = new double[ELLIPSE_SEGMENTS + 1][];
		double cos = Math.cos(ellipse.angle());
		double sin = Math.sin(ellipse.angle());
		for (int i = 0; i <= ELLIPSE_SEGMENTS; i++) {
			double t = 2 * Math.PI * i / ELLIPSE_SEGMENTS;
			double a = ellipse.semiMajor() * Math.cos(t);
			double b = ellipse.semiMinor() * Math.sin(t);
			double east = ellipse.centerEast() + a * cos - b * sin;
			double north = ellipse.centerNorth() + a * sin + b * cos;
			ring[i] = GeoMath.offset(padLatitude, padLongitude, east, north);
		}
		return ring;
	}

	private static String coordinate(double latitude, double longitude) {
		return String.format(Locale.ROOT, "%.7f,%.7f,0", longitude, latitude);
	}

	private static String escape(String text) {
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
