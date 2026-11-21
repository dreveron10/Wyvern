package info.openrocket.swing.wyvern.map;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.Window;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.filechooser.FileNameExtensionFilter;

import net.miginfocom.swing.MigLayout;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.simulation.montecarlo.DispersionStatistics;
import info.openrocket.core.simulation.montecarlo.LandingBody;
import info.openrocket.core.simulation.montecarlo.LandingPoint;
import info.openrocket.core.simulation.montecarlo.MonteCarloResult;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.Unit;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.wyvern.map.GeoMath;
import info.openrocket.core.wyvern.map.KmlWriter;
import info.openrocket.core.wyvern.map.LaunchSite;
import info.openrocket.swing.gui.util.GUIUtil;

/**
 * Shows a Monte Carlo landing dispersion on a real map around the launch pad.
 * <p>
 * Landing points come from the analysis as east/north offsets from the pad, so moving
 * the pad moves the whole cloud. The pad position is the simulation's launch site; when
 * the user moves the pad it is written back to the simulation options.
 */
public class LandingMapDialog extends JDialog {
	private static final Translator trans = Application.getTranslator();

	/** Okabe-Ito colors, distinguishable with color vision deficiencies; bodies also differ by marker shape. */
	private static final Color[] BODY_COLORS = {
			new Color(0x56, 0xB4, 0xE9), new Color(0xE6, 0x9F, 0x00), new Color(0x00, 0x9E, 0x73),
			new Color(0xCC, 0x79, 0xA7), new Color(0xF0, 0xE4, 0x42), new Color(0xD5, 0x5E, 0x00) };

	private final String simulationName;
	private final MonteCarloResult result;
	private final Simulation simulation;
	private final List<LandingBody> bodies;
	private final Unit distanceUnit = UnitGroup.UNITS_DISTANCE.getDefaultUnit();

	private double padLatitude;
	private double padLongitude;

	/** Application preference keys: the saved launch sites and the field outline last drawn. */
	private static final String PREF_SITES = "Wyvern.launchSites";
	private static final String PREF_BOUNDARY = "Wyvern.map.fieldBoundary";
	private static final int MAX_OFFLINE_TILES = 400;

	private final TileProvider tiles = new TileProvider();
	private List<LaunchSite> sites = new ArrayList<>();
	private List<double[]> boundary = new ArrayList<>();
	private boolean updatingSites = false;
	private final JComboBox<String> siteCombo = new JComboBox<>();
	private final JToggleButton drawField = new JToggleButton(trans.get("Wyvern.map.but.drawField"));
	private final JLabel offlineLabel = new JLabel(" ");

	private final MapPanel map;
	private final JComboBox<String> bodyCombo = new JComboBox<>();
	private final JCheckBox showPoints = new JCheckBox(trans.get("Wyvern.map.chk.points"), true);
	private final JCheckBox showEllipses = new JCheckBox(trans.get("Wyvern.map.chk.ellipses"), true);
	private final JCheckBox showR90 = new JCheckBox(trans.get("Wyvern.map.chk.r90"), true);
	private final JTextField latitudeField = new JTextField(10);
	private final JTextField longitudeField = new JTextField(10);
	private final JToggleButton pickPad = new JToggleButton(trans.get("Wyvern.map.but.pickPad"));
	private final JLabel statsLabel = new JLabel(" ");

	/**
	 * @param owner          parent window
	 * @param simulationName name shown in the title and exports
	 * @param result         the Monte Carlo result to show
	 * @param simulation     the simulation whose launch site anchors the map
	 */
	public LandingMapDialog(Window owner, String simulationName, MonteCarloResult result, Simulation simulation) {
		super(owner, trans.get("Wyvern.map.title") + " - " + simulationName, ModalityType.APPLICATION_MODAL);
		this.simulationName = simulationName;
		this.result = result;
		this.simulation = simulation;
		this.bodies = result.getLandingBodies();
		SimulationOptions options = simulation.getOptions();
		this.padLatitude = options.getLaunchLatitude();
		this.padLongitude = options.getLaunchLongitude();

		map = new MapPanel(tiles, padLatitude, padLongitude);
		sites = LaunchSite.listFromJson(Application.getPreferences().getString(PREF_SITES, ""));
		LaunchSite last = LaunchSite.fromJson(Application.getPreferences().getString(PREF_BOUNDARY, ""));
		if (last != null) {
			boundary = new ArrayList<>(last.boundary);
		}
		map.setPreferredSize(new Dimension(900, 560));
		map.addLayer(this::paintOverlay);

		bodyCombo.addItem(trans.get("Wyvern.map.allBodies"));
		for (LandingBody body : bodies) {
			bodyCombo.addItem(body.branchName());
		}
		if (bodies.size() == 1) {
			bodyCombo.setSelectedIndex(1);
			bodyCombo.setEnabled(false);
		}
		bodyCombo.addActionListener(e -> refresh());
		showPoints.addActionListener(e -> refresh());
		showEllipses.addActionListener(e -> refresh());
		showR90.addActionListener(e -> refresh());

		latitudeField.addActionListener(e -> padFromFields());
		longitudeField.addActionListener(e -> padFromFields());
		pickPad.setToolTipText(trans.get("Wyvern.map.but.pickPad.ttip"));
		pickPad.addActionListener(e -> {
			drawField.setSelected(false);
			map.setClickHandler(pickPad.isSelected() ? position -> {
				setPad(position[0], position[1]);
				pickPad.setSelected(false);
				map.setClickHandler(null);
			} : null);
		});

		drawField.setToolTipText(trans.get("Wyvern.map.but.drawField.ttip"));
		drawField.addActionListener(e -> {
			pickPad.setSelected(false);
			if (drawField.isSelected()) {
				boundary = new ArrayList<>();
				map.setClickHandler(position -> {
					boundary.add(position);
					refresh();
				});
			} else {
				map.setClickHandler(null);
				storeBoundary();
			}
			refresh();
		});
		JButton clearField = new JButton(trans.get("Wyvern.map.but.clearField"));
		clearField.addActionListener(e -> {
			boundary = new ArrayList<>();
			storeBoundary();
			refresh();
		});
		JButton saveSite = new JButton(trans.get("Wyvern.map.but.saveSite"));
		saveSite.addActionListener(e -> saveSite());
		JButton deleteSite = new JButton(trans.get("Wyvern.map.but.deleteSite"));
		deleteSite.addActionListener(e -> deleteSite());
		JButton offline = new JButton(trans.get("Wyvern.map.but.offline"));
		offline.setToolTipText(trans.get("Wyvern.map.but.offline.ttip"));
		offline.addActionListener(e -> saveForOffline());
		updateSiteCombo(-1);
		siteCombo.addActionListener(e -> {
			int index = siteCombo.getSelectedIndex() - 1;
			if (!updatingSites && index >= 0 && index < sites.size()) {
				LaunchSite site = sites.get(index);
				boundary = new ArrayList<>(site.boundary);
				storeBoundary();
				setPad(site.latitude, site.longitude);
				fit();
			}
		});

		JPanel layerRow = new JPanel(new MigLayout("ins 0"));
		layerRow.add(new JLabel(trans.get("Wyvern.map.lbl.body")));
		layerRow.add(bodyCombo, "gapright para");
		layerRow.add(showPoints);
		layerRow.add(showEllipses);
		layerRow.add(showR90);
		JPanel padRow = new JPanel(new MigLayout("ins 0"));
		padRow.add(new JLabel(trans.get("Wyvern.map.lbl.pad")));
		padRow.add(latitudeField);
		padRow.add(longitudeField);
		padRow.add(pickPad);
		JPanel controls = new JPanel(new MigLayout("ins 0, fillx"));
		controls.add(layerRow, "wrap");
		controls.add(padRow, "wrap");
		JPanel fieldRow = new JPanel(new MigLayout("ins 0"));
		fieldRow.add(new JLabel(trans.get("Wyvern.map.lbl.site")));
		fieldRow.add(siteCombo, "w 160lp");
		fieldRow.add(saveSite);
		fieldRow.add(deleteSite, "gapright para");
		fieldRow.add(drawField);
		fieldRow.add(clearField, "gapright para");
		fieldRow.add(offline);
		fieldRow.add(offlineLabel);
		controls.add(fieldRow, "wrap");
		controls.add(statsLabel, "growx");

		JButton fit = new JButton(trans.get("Wyvern.map.but.fit"));
		fit.addActionListener(e -> fit());
		JButton kml = new JButton(trans.get("Wyvern.map.but.kml"));
		kml.addActionListener(e -> exportKml());
		JButton image = new JButton(trans.get("Wyvern.map.but.image"));
		image.addActionListener(e -> exportImage());
		JButton close = new JButton(trans.get("dlg.but.close"));
		close.addActionListener(e -> dispose());

		JPanel panel = new JPanel(new MigLayout("fill, ins dialog"));
		panel.add(controls, "growx, wrap");
		panel.add(map, "grow, push, wrap para");
		panel.add(fit, "split 4");
		panel.add(kml);
		panel.add(image);
		panel.add(close, "gapleft push");
		setContentPane(panel);

		updateFields();
		refresh();
		GUIUtil.setDisposableDialogOptions(this, close);
		setLocationRelativeTo(owner);
		fit();
	}

	////////  Pad  ////////

	private void padFromFields() {
		try {
			double latitude = Double.parseDouble(latitudeField.getText().trim().replace(',', '.'));
			double longitude = Double.parseDouble(longitudeField.getText().trim().replace(',', '.'));
			if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
				throw new NumberFormatException();
			}
			setPad(latitude, longitude);
			fit();
		} catch (NumberFormatException e) {
			JOptionPane.showMessageDialog(this, trans.get("Wyvern.map.msg.badCoordinates"),
					trans.get("Wyvern.map.title"), JOptionPane.WARNING_MESSAGE);
			updateFields();
		}
	}

	/** Move the pad and store the new launch site in the simulation. */
	private void setPad(double latitude, double longitude) {
		padLatitude = latitude;
		padLongitude = longitude;
		SimulationOptions options = simulation.getOptions();
		options.setLaunchLatitude(latitude);
		options.setLaunchLongitude(longitude);
		updateFields();
		refresh();
	}

	private void updateFields() {
		latitudeField.setText(String.format(Locale.ROOT, "%.6f", padLatitude));
		longitudeField.setText(String.format(Locale.ROOT, "%.6f", padLongitude));
	}

	////////  Field and sites  ////////

	/** The field as it is now: current pad and the outline being shown. */
	private LaunchSite field() {
		LaunchSite field = new LaunchSite("", padLatitude, padLongitude);
		field.boundary = boundary;
		return field;
	}

	private void storeBoundary() {
		Application.getPreferences().putString(PREF_BOUNDARY, field().toJson());
	}

	private void updateSiteCombo(int select) {
		updatingSites = true;
		siteCombo.removeAllItems();
		siteCombo.addItem(trans.get("Wyvern.map.site.none"));
		for (LaunchSite site : sites) {
			siteCombo.addItem(site.name);
		}
		siteCombo.setSelectedIndex(select + 1);
		updatingSites = false;
	}

	private void saveSite() {
		int current = siteCombo.getSelectedIndex() - 1;
		String suggestion = current >= 0 ? sites.get(current).name : "";
		Object answer = JOptionPane.showInputDialog(this, trans.get("Wyvern.map.msg.siteName"),
				trans.get("Wyvern.map.but.saveSite"), JOptionPane.PLAIN_MESSAGE, null, null, suggestion);
		if (answer == null || answer.toString().isBlank()) {
			return;
		}
		String name = answer.toString().trim();
		LaunchSite site = new LaunchSite(name, padLatitude, padLongitude);
		site.boundary = new ArrayList<>(boundary);
		int index = -1;
		for (int i = 0; i < sites.size(); i++) {
			if (sites.get(i).name.equalsIgnoreCase(name)) {
				index = i;
			}
		}
		if (index >= 0) {
			sites.set(index, site);
		} else {
			sites.add(site);
			index = sites.size() - 1;
		}
		Application.getPreferences().putString(PREF_SITES, LaunchSite.listToJson(sites));
		updateSiteCombo(index);
	}

	private void deleteSite() {
		int index = siteCombo.getSelectedIndex() - 1;
		if (index < 0 || index >= sites.size()) {
			return;
		}
		if (JOptionPane.showConfirmDialog(this,
				String.format(trans.get("Wyvern.map.msg.deleteSite"), sites.get(index).name),
				trans.get("Wyvern.map.but.deleteSite"), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
			return;
		}
		sites.remove(index);
		Application.getPreferences().putString(PREF_SITES, LaunchSite.listToJson(sites));
		updateSiteCombo(-1);
	}

	/** Download the tiles of the current view, at this zoom and two levels closer, for use without a connection. */
	private void saveForOffline() {
		List<int[]> wanted = new ArrayList<>();
		for (int z = map.getZoom(); z <= Math.min(map.getZoom() + 2, MapPanel.MAX_ZOOM); z++) {
			wanted.addAll(map.visibleTiles(z));
		}
		if (wanted.size() > MAX_OFFLINE_TILES) {
			JOptionPane.showMessageDialog(this, trans.get("Wyvern.map.msg.offlineTooLarge"),
					trans.get("Wyvern.map.title"), JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		int total = wanted.size();
		offlineLabel.setText(String.format(trans.get("Wyvern.map.lbl.offlineProgress"), 0, total));
		tiles.prefetch(wanted, progress -> offlineLabel.setText(progress[0] < total
				? String.format(trans.get("Wyvern.map.lbl.offlineProgress"), progress[0], total)
				: String.format(trans.get("Wyvern.map.lbl.offlineDone"), progress[1], total)));
	}

	////////  Data  ////////

	/** Indices of the bodies to show: the selected one, or all of them. */
	private List<Integer> shownBodies() {
		List<Integer> shown = new ArrayList<>();
		int selected = bodyCombo.getSelectedIndex();
		for (int i = 0; i < bodies.size(); i++) {
			if (selected <= 0 || selected - 1 == i) {
				shown.add(i);
			}
		}
		return shown;
	}

	private List<LandingPoint> points(int body) {
		return result.getLandingPoints(bodies.get(body).bodyId());
	}

	private void refresh() {
		List<String> parts = new ArrayList<>();
		for (int body : shownBodies()) {
			List<LandingPoint> points = points(body);
			if (points.size() < 2) {
				continue;
			}
			DispersionStatistics stats = DispersionStatistics.from(points);
			String line = String.format(trans.get("Wyvern.map.lbl.stats"), bodies.get(body).branchName(), points.size(),
					distanceUnit.toStringUnit(stats.getMeanRangeFromPad()),
					distanceUnit.toStringUnit(stats.getContainmentRadius(0.9)));
			double inside = field().fractionInside(padLatitude, padLongitude, points);
			if (!Double.isNaN(inside) && !drawField.isSelected()) {
				line += ", " + String.format(trans.get("Wyvern.map.lbl.inside"), Math.round(1000 * inside) / 10.0);
			}
			parts.add(line);
		}
		statsLabel.setText(parts.isEmpty() ? trans.get("Wyvern.map.lbl.noPoints") : String.join("   ", parts));
		map.repaint();
	}

	private void fit() {
		List<double[]> positions = new ArrayList<>();
		positions.add(new double[] { padLatitude, padLongitude });
		for (int body : shownBodies()) {
			List<LandingPoint> points = points(body);
			for (LandingPoint p : points) {
				positions.add(GeoMath.offset(padLatitude, padLongitude, p.east(), p.north()));
			}
			if (points.size() >= 2) {
				DispersionStatistics stats = DispersionStatistics.from(points);
				for (double[] p : KmlWriter.ellipse(stats.getEllipse(3), padLatitude, padLongitude)) {
					positions.add(p);
				}
			}
		}
		positions.addAll(boundary);
		if (positions.size() == 1) {
			map.setZoom(15);
			map.setCenter(padLatitude, padLongitude);
		} else {
			map.fit(positions);
		}
	}

	////////  Painting  ////////

	private void paintField(Graphics2D g, MapPanel panel) {
		if (boundary.isEmpty()) {
			return;
		}
		Path2D outline = new Path2D.Double();
		for (int i = 0; i < boundary.size(); i++) {
			Point2D p = panel.toScreen(boundary.get(i)[0], boundary.get(i)[1]);
			if (i == 0) {
				outline.moveTo(p.getX(), p.getY());
			} else {
				outline.lineTo(p.getX(), p.getY());
			}
		}
		Color amber = new Color(0xF2, 0xA3, 0x3A);
		if (boundary.size() >= 3) {
			outline.closePath();
			g.setColor(new Color(amber.getRed(), amber.getGreen(), amber.getBlue(), 38));
			g.fill(outline);
		}
		outlined(g, outline, amber, new BasicStroke(2.5f));
		if (drawField.isSelected()) {
			for (double[] vertex : boundary) {
				Point2D p = panel.toScreen(vertex[0], vertex[1]);
				Shape handle = new Rectangle2D.Double(p.getX() - 4, p.getY() - 4, 8, 8);
				g.setColor(Color.WHITE);
				g.fill(handle);
				g.setColor(Color.BLACK);
				g.setStroke(new BasicStroke(1f));
				g.draw(handle);
			}
		} else if (boundary.size() >= 3) {
			Point2D p = panel.toScreen(boundary.get(0)[0], boundary.get(0)[1]);
			label(g, trans.get("Wyvern.map.field"), p.getX() + 6, p.getY() - 6);
		}
	}

	private void paintOverlay(Graphics2D g, MapPanel panel) {
		paintField(g, panel);
		for (int body : shownBodies()) {
			Color color = BODY_COLORS[body % BODY_COLORS.length];
			List<LandingPoint> points = points(body);

			if (points.size() >= 2 && (showEllipses.isSelected() || showR90.isSelected())) {
				DispersionStatistics stats = DispersionStatistics.from(points);
				if (showEllipses.isSelected()) {
					// 1, 2 and 3 sigma are told apart by line style as well as by their size
					Stroke[] strokes = { new BasicStroke(2.2f),
							new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[] { 9, 5 }, 0),
							new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[] { 2, 4 }, 0) };
					for (int sigma = 1; sigma <= 3; sigma++) {
						Path2D path = new Path2D.Double();
						double[][] ring = KmlWriter.ellipse(stats.getEllipse(sigma), padLatitude, padLongitude);
						for (int i = 0; i < ring.length; i++) {
							Point2D p = panel.toScreen(ring[i][0], ring[i][1]);
							if (i == 0) {
								path.moveTo(p.getX(), p.getY());
							} else {
								path.lineTo(p.getX(), p.getY());
							}
						}
						outlined(g, path, color, strokes[sigma - 1]);
					}
				}
				if (showR90.isSelected()) {
					double[] mean = GeoMath.offset(padLatitude, padLongitude, stats.getMeanEast(), stats.getMeanNorth());
					Point2D center = panel.toScreen(mean[0], mean[1]);
					double radius = stats.getContainmentRadius(0.9) * panel.pixelsPerMeter();
					Shape circle = new Ellipse2D.Double(center.getX() - radius, center.getY() - radius, 2 * radius, 2 * radius);
					outlined(g, circle, Color.WHITE,
							new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[] { 12, 4, 2, 4 }, 0));
					label(g, "R90", center.getX() + radius * 0.707 + 4, center.getY() - radius * 0.707 - 4);
				}
			}

			if (showPoints.isSelected()) {
				for (LandingPoint point : points) {
					double[] position = GeoMath.offset(padLatitude, padLongitude, point.east(), point.north());
					Point2D p = panel.toScreen(position[0], position[1]);
					Shape marker = marker(body, p.getX(), p.getY(), 4.5);
					g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 190));
					g.fill(marker);
					g.setColor(new Color(0, 0, 0, 170));
					g.setStroke(new BasicStroke(0.8f));
					g.draw(marker);
				}
			}
		}
		paintPad(g, panel);
		paintLegend(g, panel);
	}

	/** Draw a line with a dark halo so it stays visible on light and dark map tiles. */
	private static void outlined(Graphics2D g, Shape shape, Color color, Stroke stroke) {
		// The halo follows the dash pattern, so dashed and dotted lines stay recognizable
		Stroke halo = new BasicStroke(4.5f);
		if (stroke instanceof BasicStroke basic && basic.getDashArray() != null) {
			halo = new BasicStroke(4.5f, basic.getEndCap(), basic.getLineJoin(), basic.getMiterLimit(),
					basic.getDashArray(), basic.getDashPhase());
		}
		g.setColor(new Color(0, 0, 0, 150));
		g.setStroke(halo);
		g.draw(shape);
		g.setColor(color);
		g.setStroke(stroke);
		g.draw(shape);
	}

	/** A different marker shape per body: circle, triangle, square, diamond. */
	private static Shape marker(int body, double x, double y, double r) {
		switch (body % 4) {
		case 1: {
			Path2D triangle = new Path2D.Double();
			triangle.moveTo(x, y - r * 1.2);
			triangle.lineTo(x + r * 1.1, y + r * 0.9);
			triangle.lineTo(x - r * 1.1, y + r * 0.9);
			triangle.closePath();
			return triangle;
		}
		case 2:
			return new Rectangle2D.Double(x - r * 0.9, y - r * 0.9, 1.8 * r, 1.8 * r);
		case 3: {
			Path2D diamond = new Path2D.Double();
			diamond.moveTo(x, y - r * 1.3);
			diamond.lineTo(x + r * 1.3, y);
			diamond.lineTo(x, y + r * 1.3);
			diamond.lineTo(x - r * 1.3, y);
			diamond.closePath();
			return diamond;
		}
		default:
			return new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r);
		}
	}

	private void paintPad(Graphics2D g, MapPanel panel) {
		Point2D p = panel.toScreen(padLatitude, padLongitude);
		double x = p.getX();
		double y = p.getY();
		g.setStroke(new BasicStroke(5f));
		g.setColor(new Color(0, 0, 0, 160));
		g.draw(new Ellipse2D.Double(x - 8, y - 8, 16, 16));
		g.drawLine((int) x - 13, (int) y, (int) x + 13, (int) y);
		g.drawLine((int) x, (int) y - 13, (int) x, (int) y + 13);
		g.setStroke(new BasicStroke(2f));
		g.setColor(Color.WHITE);
		g.draw(new Ellipse2D.Double(x - 8, y - 8, 16, 16));
		g.drawLine((int) x - 13, (int) y, (int) x + 13, (int) y);
		g.drawLine((int) x, (int) y - 13, (int) x, (int) y + 13);
		label(g, trans.get("Wyvern.map.pad"), x + 12, y - 12);
	}

	private static void label(Graphics2D g, String text, double x, double y) {
		g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
		FontMetrics fm = g.getFontMetrics();
		g.setColor(new Color(0, 0, 0, 170));
		g.fillRoundRect((int) x - 3, (int) y - fm.getAscent() - 1, fm.stringWidth(text) + 6, fm.getHeight() + 2, 6, 6);
		g.setColor(Color.WHITE);
		g.drawString(text, (float) x, (float) y);
	}

	private void paintLegend(Graphics2D g, MapPanel panel) {
		List<Integer> shown = shownBodies();
		g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
		FontMetrics fm = g.getFontMetrics();
		List<String> lines = new ArrayList<>();
		for (int body : shown) {
			lines.add(bodies.get(body).branchName());
		}
		if (showEllipses.isSelected()) {
			lines.add(trans.get("Wyvern.map.legend.ellipses"));
		}
		int width = 0;
		for (String line : lines) {
			width = Math.max(width, fm.stringWidth(line));
		}
		int rowHeight = fm.getHeight() + 4;
		int x = 12;
		int y = 12;
		g.setColor(new Color(0, 0, 0, 160));
		g.fillRoundRect(x - 6, y - 6, width + 40, rowHeight * lines.size() + 10, 8, 8);
		for (int i = 0; i < lines.size(); i++) {
			int rowY = y + i * rowHeight + rowHeight / 2;
			if (i < shown.size()) {
				int body = shown.get(i);
				g.setColor(BODY_COLORS[body % BODY_COLORS.length]);
				g.fill(marker(body, x + 8, rowY, 5));
			}
			g.setColor(Color.WHITE);
			g.drawString(lines.get(i), x + 24, rowY + fm.getAscent() / 2 - 1);
		}
	}

	////////  Export  ////////

	private File chooseFile(String extension, String description) {
		JFileChooser chooser = new JFileChooser();
		chooser.setFileFilter(new FileNameExtensionFilter(description, extension));
		String base = simulationName.replaceAll("[^A-Za-z0-9 _.-]", "_");
		chooser.setSelectedFile(new File(base + " landing map." + extension));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
			return null;
		}
		File file = chooser.getSelectedFile();
		if (!file.getName().toLowerCase(Locale.ROOT).endsWith("." + extension)) {
			file = new File(file.getParentFile(), file.getName() + "." + extension);
		}
		if (file.exists() && JOptionPane.showConfirmDialog(this,
				String.format(trans.get("Wyvern.map.msg.overwrite"), file.getName()), trans.get("Wyvern.map.title"),
				JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
			return null;
		}
		return file;
	}

	private void exportKml() {
		File file = chooseFile("kml", "KML (Google Earth)");
		if (file == null) {
			return;
		}
		try {
			Files.writeString(file.toPath(), KmlWriter.write(simulationName, padLatitude, padLongitude, result),
					StandardCharsets.UTF_8);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, String.format(trans.get("Wyvern.map.msg.saveFailed"), e.getMessage()),
					trans.get("Wyvern.map.title"), JOptionPane.ERROR_MESSAGE);
		}
	}

	private void exportImage() {
		File file = chooseFile("png", "PNG");
		if (file == null) {
			return;
		}
		try {
			ImageIO.write(map.snapshot(), "png", file);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, String.format(trans.get("Wyvern.map.msg.saveFailed"), e.getMessage()),
					trans.get("Wyvern.map.title"), JOptionPane.ERROR_MESSAGE);
		}
	}
}
