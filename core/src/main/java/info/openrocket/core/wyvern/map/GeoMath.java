package info.openrocket.core.wyvern.map;

/**
 * Coordinate math for the landing map: local east/north offsets around a launch site,
 * and the Web Mercator projection used by slippy map tiles.
 * <p>
 * Latitudes and longitudes are in degrees, distances in meters. Tile pixel coordinates
 * follow the usual convention: 256 pixel tiles, x growing east from longitude -180,
 * y growing south from latitude +85.0511.
 */
public final class GeoMath {

	/** WGS 84 equatorial radius in m, as used by Web Mercator. */
	public static final double EARTH_RADIUS = 6378137.0;

	/** Tile edge length in pixels. */
	public static final int TILE_SIZE = 256;

	/** Northern and southern limit of the Web Mercator projection in degrees. */
	public static final double MAX_LATITUDE = 85.05112878;

	private GeoMath() {
	}

	/**
	 * Position reached by moving east and north from a point, on a local flat-earth
	 * frame. At landing distances of a few kilometers the error is far below a meter.
	 *
	 * @return {latitude, longitude} in degrees
	 */
	public static double[] offset(double latitude, double longitude, double east, double north) {
		double lat = latitude + Math.toDegrees(north / EARTH_RADIUS);
		double lon = longitude + Math.toDegrees(east / (EARTH_RADIUS * Math.cos(Math.toRadians(latitude))));
		return new double[] { lat, lon };
	}

	/**
	 * Inverse of {@link #offset}: east and north displacement of a point from an origin.
	 *
	 * @return {east, north} in m
	 */
	public static double[] toLocal(double originLatitude, double originLongitude, double latitude, double longitude) {
		double north = Math.toRadians(latitude - originLatitude) * EARTH_RADIUS;
		double east = Math.toRadians(longitude - originLongitude) * EARTH_RADIUS
				* Math.cos(Math.toRadians(originLatitude));
		return new double[] { east, north };
	}

	/** Width and height of the whole world in pixels at a zoom level. */
	public static double worldSize(int zoom) {
		return TILE_SIZE * Math.pow(2, zoom);
	}

	/** Web Mercator pixel x of a longitude at a zoom level. */
	public static double worldX(double longitude, int zoom) {
		return (longitude + 180.0) / 360.0 * worldSize(zoom);
	}

	/** Web Mercator pixel y of a latitude at a zoom level. Latitudes beyond the projection limit are clamped. */
	public static double worldY(double latitude, int zoom) {
		double lat = Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
		double sin = Math.sin(Math.toRadians(lat));
		return (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * worldSize(zoom);
	}

	/** Longitude of a Web Mercator pixel x at a zoom level. */
	public static double longitude(double worldX, int zoom) {
		return worldX / worldSize(zoom) * 360.0 - 180.0;
	}

	/** Latitude of a Web Mercator pixel y at a zoom level. */
	public static double latitude(double worldY, int zoom) {
		double n = Math.PI - 2 * Math.PI * worldY / worldSize(zoom);
		return Math.toDegrees(Math.atan(Math.sinh(n)));
	}

	/** Ground distance covered by one pixel at a latitude and zoom level, in m. */
	public static double metersPerPixel(double latitude, int zoom) {
		return 2 * Math.PI * EARTH_RADIUS * Math.cos(Math.toRadians(latitude)) / worldSize(zoom);
	}
}
