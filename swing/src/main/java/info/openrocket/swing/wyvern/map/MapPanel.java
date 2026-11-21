package info.openrocket.swing.wyvern.map;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JPanel;

import info.openrocket.core.wyvern.map.GeoMath;

/**
 * A pannable, zoomable slippy map that draws tiles from a {@link TileProvider} and
 * lets layers paint on top in geographic coordinates.
 * <p>
 * Drag to pan, use the wheel or double-click to zoom. A click handler can be set to
 * pick a position.
 */
public class MapPanel extends JPanel {

	/** Something drawn on top of the map. */
	public interface Layer {
		void paint(Graphics2D g, MapPanel map);
	}

	public static final int MIN_ZOOM = 2;
	public static final int MAX_ZOOM = 19;

	private final TileProvider tiles;
	private final List<Layer> layers = new ArrayList<>();
	private double centerLatitude;
	private double centerLongitude;
	private int zoom = 14;
	private Consumer<double[]> clickHandler;
	private Point dragStart;
	private boolean dragged;

	public MapPanel(TileProvider tiles, double latitude, double longitude) {
		this.tiles = tiles;
		this.centerLatitude = latitude;
		this.centerLongitude = longitude;
		setBackground(new Color(0x2a, 0x2f, 0x3a));
		setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));

		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				dragStart = e.getPoint();
				dragged = false;
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				if (dragStart == null) {
					return;
				}
				double x = GeoMath.worldX(centerLongitude, zoom) - (e.getX() - dragStart.x);
				double y = GeoMath.worldY(centerLatitude, zoom) - (e.getY() - dragStart.y);
				setCenterWorld(x, y);
				dragStart = e.getPoint();
				dragged = true;
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				if (dragged) {
					return;
				}
				if (clickHandler != null) {
					clickHandler.accept(toGeo(e.getX(), e.getY()));
				} else if (e.getClickCount() == 2) {
					zoomAround(e.getPoint(), 1);
				}
			}

			@Override
			public void mouseWheelMoved(MouseWheelEvent e) {
				zoomAround(e.getPoint(), e.getWheelRotation() < 0 ? 1 : -1);
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		addMouseWheelListener(mouse);
	}

	public void addLayer(Layer layer) {
		layers.add(layer);
		repaint();
	}

	/**
	 * Set what a click does. While a handler is set, clicks report the clicked position
	 * as {latitude, longitude} instead of zooming. Pass null to remove the handler.
	 */
	public void setClickHandler(Consumer<double[]> handler) {
		this.clickHandler = handler;
		setCursor(Cursor.getPredefinedCursor(handler == null ? Cursor.MOVE_CURSOR : Cursor.CROSSHAIR_CURSOR));
	}

	public int getZoom() {
		return zoom;
	}

	public void setZoom(int zoom) {
		this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
		repaint();
	}

	public void setCenter(double latitude, double longitude) {
		this.centerLatitude = Math.max(-GeoMath.MAX_LATITUDE, Math.min(GeoMath.MAX_LATITUDE, latitude));
		this.centerLongitude = longitude;
		repaint();
	}

	private void setCenterWorld(double worldX, double worldY) {
		double size = GeoMath.worldSize(zoom);
		double y = Math.max(0, Math.min(size, worldY));
		setCenter(GeoMath.latitude(y, zoom), GeoMath.longitude(worldX, zoom));
	}

	/** Zoom in or out by whole levels, keeping the position under a screen point fixed. */
	public void zoomAround(Point screen, int levels) {
		int target = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + levels));
		if (target == zoom) {
			return;
		}
		double[] anchor = toGeo(screen.x, screen.y);
		zoom = target;
		double x = GeoMath.worldX(anchor[1], zoom) - (screen.x - getWidth() / 2.0);
		double y = GeoMath.worldY(anchor[0], zoom) - (screen.y - getHeight() / 2.0);
		setCenterWorld(x, y);
	}

	/**
	 * Center and zoom so that every given position is visible with some margin.
	 *
	 * @param positions {latitude, longitude} pairs; ignored if empty
	 */
	public void fit(List<double[]> positions) {
		if (positions.isEmpty()) {
			return;
		}
		double minLat = Double.MAX_VALUE;
		double maxLat = -Double.MAX_VALUE;
		double minLon = Double.MAX_VALUE;
		double maxLon = -Double.MAX_VALUE;
		for (double[] p : positions) {
			minLat = Math.min(minLat, p[0]);
			maxLat = Math.max(maxLat, p[0]);
			minLon = Math.min(minLon, p[1]);
			maxLon = Math.max(maxLon, p[1]);
		}
		int width = Math.max(getWidth(), 200);
		int height = Math.max(getHeight(), 200);
		int best = MIN_ZOOM;
		for (int z = MAX_ZOOM; z >= MIN_ZOOM; z--) {
			double w = GeoMath.worldX(maxLon, z) - GeoMath.worldX(minLon, z);
			double h = GeoMath.worldY(minLat, z) - GeoMath.worldY(maxLat, z);
			if (w <= width * 0.8 && h <= height * 0.8) {
				best = z;
				break;
			}
		}
		zoom = best;
		setCenter((minLat + maxLat) / 2, (minLon + maxLon) / 2);
	}

	/** Screen position of a geographic position. */
	public Point2D toScreen(double latitude, double longitude) {
		double x = GeoMath.worldX(longitude, zoom) - GeoMath.worldX(centerLongitude, zoom) + getWidth() / 2.0;
		double y = GeoMath.worldY(latitude, zoom) - GeoMath.worldY(centerLatitude, zoom) + getHeight() / 2.0;
		return new Point2D.Double(x, y);
	}

	/** Geographic position of a screen point, as {latitude, longitude}. */
	public double[] toGeo(double screenX, double screenY) {
		double x = GeoMath.worldX(centerLongitude, zoom) + screenX - getWidth() / 2.0;
		double y = GeoMath.worldY(centerLatitude, zoom) + screenY - getHeight() / 2.0;
		return new double[] { GeoMath.latitude(y, zoom), GeoMath.longitude(x, zoom) };
	}

	/** Screen pixels per meter at the map center. */
	public double pixelsPerMeter() {
		return 1.0 / GeoMath.metersPerPixel(centerLatitude, zoom);
	}

	/**
	 * Tiles that cover the current view at a zoom level, as {zoom, x, y} triples.
	 * Tiles outside the world are left out.
	 */
	public List<int[]> visibleTiles(int tileZoom) {
		double[] topLeft = toGeo(0, 0);
		double[] bottomRight = toGeo(getWidth(), getHeight());
		int n = 1 << tileZoom;
		int firstX = (int) Math.floor(GeoMath.worldX(topLeft[1], tileZoom) / GeoMath.TILE_SIZE);
		int lastX = (int) Math.floor(GeoMath.worldX(bottomRight[1], tileZoom) / GeoMath.TILE_SIZE);
		int firstY = (int) Math.floor(GeoMath.worldY(topLeft[0], tileZoom) / GeoMath.TILE_SIZE);
		int lastY = (int) Math.floor(GeoMath.worldY(bottomRight[0], tileZoom) / GeoMath.TILE_SIZE);
		List<int[]> result = new ArrayList<>();
		for (int x = firstX; x <= lastX; x++) {
			for (int y = Math.max(firstY, 0); y <= Math.min(lastY, n - 1); y++) {
				result.add(new int[] { tileZoom, ((x % n) + n) % n, y });
			}
		}
		return result;
	}

	/** Render the current view, with all layers, into an image. */
	public BufferedImage snapshot() {
		BufferedImage image = new BufferedImage(Math.max(getWidth(), 1), Math.max(getHeight(), 1),
				BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		paint(g);
		g.dispose();
		return image;
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		super.paintComponent(graphics);
		Graphics2D g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		double left = GeoMath.worldX(centerLongitude, zoom) - getWidth() / 2.0;
		double top = GeoMath.worldY(centerLatitude, zoom) - getHeight() / 2.0;
		int size = GeoMath.TILE_SIZE;
		int firstX = (int) Math.floor(left / size);
		int firstY = (int) Math.floor(top / size);
		int lastX = (int) Math.floor((left + getWidth()) / size);
		int lastY = (int) Math.floor((top + getHeight()) / size);
		for (int tx = firstX; tx <= lastX; tx++) {
			for (int ty = firstY; ty <= lastY; ty++) {
				int sx = (int) Math.round(tx * size - left);
				int sy = (int) Math.round(ty * size - top);
				BufferedImage tile = tiles.getTile(zoom, tx, ty, this::repaint);
				if (tile != null) {
					g.drawImage(tile, sx, sy, size, size, null);
				} else {
					// Placeholder so the map still gives a sense of scale without tiles
					g.setColor(new Color(0x34, 0x3a, 0x46));
					g.drawRect(sx, sy, size, size);
				}
			}
		}

		for (Layer layer : layers) {
			Graphics2D lg = (Graphics2D) g.create();
			layer.paint(lg, this);
			lg.dispose();
		}

		paintScaleBar(g);
		if (tiles.isOpenStreetMap()) {
			paintAttribution(g);
		}
		g.dispose();
	}

	private void paintScaleBar(Graphics2D g) {
		double metersPerPixel = GeoMath.metersPerPixel(centerLatitude, zoom);
		double target = 120 * metersPerPixel;
		double magnitude = Math.pow(10, Math.floor(Math.log10(target)));
		double length = magnitude;
		for (double step : new double[] { 5, 2, 1 }) {
			if (step * magnitude <= target) {
				length = step * magnitude;
				break;
			}
		}
		int pixels = (int) Math.round(length / metersPerPixel);
		int x = 12;
		int y = getHeight() - 14;
		String label = length >= 1000 ? (int) (length / 1000) + " km" : (int) length + " m";
		String feet = Math.round(length / 0.3048) + " ft";
		g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
		FontMetrics fm = g.getFontMetrics();
		String text = label + " (" + feet + ")";
		g.setColor(new Color(0, 0, 0, 150));
		g.fillRoundRect(x - 6, y - 20, Math.max(pixels, fm.stringWidth(text)) + 12, 28, 6, 6);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(2f));
		g.drawLine(x, y, x + pixels, y);
		g.drawLine(x, y - 4, x, y + 1);
		g.drawLine(x + pixels, y - 4, x + pixels, y + 1);
		g.drawString(text, x, y - 7);
	}

	private void paintAttribution(Graphics2D g) {
		g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
		FontMetrics fm = g.getFontMetrics();
		int width = fm.stringWidth(TileProvider.ATTRIBUTION) + 8;
		g.setColor(new Color(255, 255, 255, 200));
		g.fillRect(getWidth() - width, getHeight() - 16, width, 16);
		g.setColor(Color.BLACK);
		g.drawString(TileProvider.ATTRIBUTION, getWidth() - width + 4, getHeight() - 4);
	}
}
