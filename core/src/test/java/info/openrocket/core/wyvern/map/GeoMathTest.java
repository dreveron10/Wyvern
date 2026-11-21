package info.openrocket.core.wyvern.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import info.openrocket.core.simulation.montecarlo.DispersionStatistics;
import info.openrocket.core.simulation.montecarlo.LandingPoint;
import info.openrocket.core.simulation.montecarlo.MonteCarloResult;
import info.openrocket.core.simulation.montecarlo.MonteCarloRunResult;
import info.openrocket.core.simulation.montecarlo.MonteCarloSettings;

public class GeoMathTest {

	// Bragg Farms, Toney, Alabama (NASA Student Launch field), approximately
	private static final double LAT = 34.895;
	private static final double LON = -86.617;

	@Test
	@DisplayName("one degree of latitude is 111.3 km and longitude shrinks with cos(latitude)")
	public void testOffsetScale() {
		// 2 pi R / 360 = 111,319.5 m per degree
		double[] north = GeoMath.offset(LAT, LON, 0, 111319.49);
		assertEquals(LAT + 1, north[0], 1e-6);
		assertEquals(LON, north[1], 1e-12);
		// At 60 degrees latitude a degree of longitude is half as long
		double[] east = GeoMath.offset(60, 10, 111319.49 / 2, 0);
		assertEquals(11.0, east[1], 1e-6);
	}

	@Test
	@DisplayName("offset and toLocal are inverses")
	public void testLocalRoundTrip() {
		double[] p = GeoMath.offset(LAT, LON, 750, -420);
		double[] local = GeoMath.toLocal(LAT, LON, p[0], p[1]);
		assertEquals(750, local[0], 1e-6);
		assertEquals(-420, local[1], 1e-6);
	}

	@Test
	@DisplayName("Web Mercator matches the known tile layout")
	public void testMercator() {
		// Zoom 0 is one 256 px tile with (0, 0) in its center
		assertEquals(128, GeoMath.worldX(0, 0), 1e-9);
		assertEquals(128, GeoMath.worldY(0, 0), 1e-9);
		assertEquals(0, GeoMath.worldX(-180, 0), 1e-9);
		assertEquals(0, GeoMath.worldY(GeoMath.MAX_LATITUDE, 0), 1e-6);
		assertEquals(256, GeoMath.worldY(-90, 0), 1e-6);
		// Published example: lat 41.85, lon -87.65 (Chicago) is in tile x=1050, y=1522 at zoom 12
		assertEquals(1050, (int) Math.floor(GeoMath.worldX(-87.65, 12) / 256));
		assertEquals(1522, (int) Math.floor(GeoMath.worldY(41.85, 12) / 256));
	}

	@Test
	@DisplayName("pixel to coordinate conversion inverts the projection")
	public void testMercatorRoundTrip() {
		for (int zoom : new int[] { 3, 12, 17 }) {
			assertEquals(LON, GeoMath.longitude(GeoMath.worldX(LON, zoom), zoom), 1e-9);
			assertEquals(LAT, GeoMath.latitude(GeoMath.worldY(LAT, zoom), zoom), 1e-9);
		}
	}

	@Test
	@DisplayName("ground resolution is 156,543 m per pixel at the equator at zoom 0 and halves per level")
	public void testMetersPerPixel() {
		assertEquals(156543.03, GeoMath.metersPerPixel(0, 0), 0.01);
		assertEquals(156543.03 / 2, GeoMath.metersPerPixel(0, 1), 0.01);
		assertEquals(156543.03 / 2, GeoMath.metersPerPixel(60, 0), 0.01);
	}

	@Test
	@DisplayName("a moved pixel and a moved meter agree at field scale")
	public void testProjectionAgreesWithLocalFrame() {
		// 1000 m east of the pad, measured through the tile projection
		double[] p = GeoMath.offset(LAT, LON, 1000, 0);
		double pixels = GeoMath.worldX(p[1], 16) - GeoMath.worldX(LON, 16);
		assertEquals(1000, pixels * GeoMath.metersPerPixel(LAT, 16), 0.01);
	}

	/** Three landings of one body at (100, 0), (300, 200) and (200, 400) m east/north; the sample details are not needed. */
	private static MonteCarloResult result() {
		MonteCarloSettings settings = MonteCarloSettings.builder().runCount(3).seed(17).build();
		MonteCarloRunResult nominal = new MonteCarloRunResult(null,
				List.of(new LandingPoint(0, "Rocket & <payload>", 100, 0)), 100, 10, null);
		MonteCarloRunResult a = new MonteCarloRunResult(null,
				List.of(new LandingPoint(0, "Rocket & <payload>", 100, 0)), 100, 10, null);
		MonteCarloRunResult b = new MonteCarloRunResult(null,
				List.of(new LandingPoint(0, "Rocket & <payload>", 300, 200)), 100, 10, null);
		MonteCarloRunResult c = new MonteCarloRunResult(null,
				List.of(new LandingPoint(0, "Rocket & <payload>", 200, 400)), 100, 10, null);
		return new MonteCarloResult(settings, nominal, List.of(a, b, c), 1);
	}

	@Test
	@DisplayName("KML holds the pad, every landing point and three ellipses, with escaped names")
	public void testKml() {
		String kml = KmlWriter.write("Test <flight>", LAT, LON, result());

		assertTrue(kml.startsWith("<?xml"));
		assertTrue(kml.contains("<name>Test &lt;flight&gt;</name>"));
		assertTrue(kml.contains("Rocket &amp; &lt;payload&gt;"));
		assertTrue(kml.contains("<name>Launch pad</name><Point><coordinates>-86.6170000,34.8950000,0"));
		assertEquals(3, kml.split("sigma</name>").length - 1);
		// Pad plus three landings
		assertEquals(4, kml.split("<Point>").length - 1);
		// First landing is 100 m east: 100 / (R cos(lat)) = 0.0010952 degrees of longitude
		assertTrue(kml.contains("<name>1</name><Point><coordinates>-86.6159048,34.8950000,0"), kml);
	}

	@Test
	@DisplayName("an ellipse outline is closed and centered on the landing mean")
	public void testEllipseRing() {
		DispersionStatistics stats = DispersionStatistics.from(result().getLandingPoints(
				result().getLandingBodies().get(0).bodyId()));
		double[][] ring = KmlWriter.ellipse(stats.getEllipse(2), LAT, LON);

		assertEquals(ring[0][0], ring[ring.length - 1][0], 1e-12);
		assertEquals(ring[0][1], ring[ring.length - 1][1], 1e-12);
		double east = 0;
		double north = 0;
		for (int i = 0; i < ring.length - 1; i++) {
			double[] local = GeoMath.toLocal(LAT, LON, ring[i][0], ring[i][1]);
			east += local[0];
			north += local[1];
		}
		// Mean of the three landings is (200, 200)
		assertEquals(200, east / (ring.length - 1), 0.01);
		assertEquals(200, north / (ring.length - 1), 0.01);
	}
}
