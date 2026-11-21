package info.openrocket.core.wyvern.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import info.openrocket.core.simulation.montecarlo.LandingPoint;

public class LaunchSiteTest {

	private static final double LAT = 34.895;
	private static final double LON = -86.617;

	/** A site whose boundary is given as east/north corners in meters around the pad. */
	private static LaunchSite site(double[][] cornersEastNorth) {
		LaunchSite site = new LaunchSite("Test field", LAT, LON);
		for (double[] corner : cornersEastNorth) {
			site.boundary.add(GeoMath.offset(LAT, LON, corner[0], corner[1]));
		}
		return site;
	}

	private static boolean containsLocal(LaunchSite site, double east, double north) {
		double[] p = GeoMath.offset(LAT, LON, east, north);
		return site.contains(p[0], p[1]);
	}

	@Test
	@DisplayName("a square field contains points inside it and not outside")
	public void testSquare() {
		LaunchSite square = site(new double[][] { { -500, -500 }, { 500, -500 }, { 500, 500 }, { -500, 500 } });
		assertTrue(square.hasBoundary());
		assertTrue(containsLocal(square, 0, 0));
		assertTrue(containsLocal(square, 499, -499));
		assertFalse(containsLocal(square, 501, 0));
		assertFalse(containsLocal(square, 0, -501));
		assertFalse(containsLocal(square, 2000, 2000));
	}

	@Test
	@DisplayName("an L-shaped field excludes its notch")
	public void testConcave() {
		// L shape: the upper right quadrant is cut out
		LaunchSite field = site(new double[][] { { 0, 0 }, { 400, 0 }, { 400, 200 }, { 200, 200 }, { 200, 400 },
				{ 0, 400 } });
		assertTrue(containsLocal(field, 100, 100));
		assertTrue(containsLocal(field, 300, 100));
		assertTrue(containsLocal(field, 100, 300));
		assertFalse(containsLocal(field, 300, 300));
	}

	@Test
	@DisplayName("fraction inside counts landing points given as offsets from the pad")
	public void testFractionInside() {
		LaunchSite square = site(new double[][] { { -500, -500 }, { 500, -500 }, { 500, 500 }, { -500, 500 } });
		List<LandingPoint> points = List.of(new LandingPoint(0, "Rocket", 0, 0), new LandingPoint(0, "Rocket", 100, -300),
				new LandingPoint(0, "Rocket", 700, 0), new LandingPoint(0, "Rocket", -100, 900));
		assertEquals(0.5, square.fractionInside(LAT, LON, points), 1e-12);

		// Moving the pad 600 m east carries the whole cloud with it: only (-100, 900) + 600 east is still outside,
		// (0,0) -> 600 east is outside, (100,-300) -> 700 outside, (700,0) -> 1300 outside
		double[] moved = GeoMath.offset(LAT, LON, 600, 0);
		assertEquals(0.0, square.fractionInside(moved[0], moved[1], points), 1e-12);

		assertTrue(Double.isNaN(square.fractionInside(LAT, LON, List.of())));
		assertTrue(Double.isNaN(new LaunchSite("No field", LAT, LON).fractionInside(LAT, LON, points)));
	}

	@Test
	@DisplayName("sites survive a JSON round trip and bad input is dropped")
	public void testJson() {
		LaunchSite square = site(new double[][] { { -500, -500 }, { 500, -500 }, { 500, 500 } });
		LaunchSite back = LaunchSite.fromJson(square.toJson());
		assertEquals("Test field", back.name);
		assertEquals(LAT, back.latitude, 0);
		assertEquals(3, back.boundary.size());
		assertEquals(square.boundary.get(1)[1], back.boundary.get(1)[1], 0);

		List<LaunchSite> list = LaunchSite.listFromJson(LaunchSite.listToJson(List.of(square, new LaunchSite("B", 1, 2))));
		assertEquals(2, list.size());
		assertFalse(list.get(1).hasBoundary());

		assertNull(LaunchSite.fromJson("{ not json"));
		assertNull(LaunchSite.fromJson("{\"name\":\"x\",\"latitude\":200,\"longitude\":0}"));
		assertTrue(LaunchSite.listFromJson("garbage").isEmpty());
		assertTrue(LaunchSite.listFromJson(null).isEmpty());
		assertEquals(1, LaunchSite.listFromJson(
				"[{\"name\":\"ok\",\"latitude\":1,\"longitude\":2},{\"name\":\"bad\",\"latitude\":95,\"longitude\":2}]").size());
	}
}
