package info.openrocket.swing.wyvern.flightlog;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

import net.miginfocom.swing.MigLayout;

import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.RectangleAnchor;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.Unit;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.wyvern.flightlog.FlightLog;
import info.openrocket.core.wyvern.flightlog.FlightLogReader;
import info.openrocket.core.wyvern.flightlog.FlightLogReader.ColumnMapping;
import info.openrocket.core.wyvern.flightlog.FlightLogStats;
import info.openrocket.core.wyvern.flightlog.SimulationLog;
import info.openrocket.swing.gui.util.GUIUtil;

/**
 * Lays a recorded altimeter log over a simulation: altitude and vertical velocity
 * against time, and a table comparing the numbers that matter for recovery.
 */
public class FlightLogDialog extends JDialog {
	private static final Translator trans = Application.getTranslator();

	/** Okabe-Ito sky blue and orange; the flight line is also dashed so the two differ without color. */
	private static final Color SIMULATION_COLOR = new Color(0x56, 0xB4, 0xE9);
	private static final Color FLIGHT_COLOR = new Color(0xE6, 0x9F, 0x00);
	private static final BasicStroke SIMULATION_STROKE = new BasicStroke(2f);
	private static final BasicStroke FLIGHT_STROKE = new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
			1f, new float[] { 7f, 4f }, 0f);

	private final Unit distanceUnit = UnitGroup.UNITS_DISTANCE.getDefaultUnit();
	private final Unit velocityUnit = UnitGroup.UNITS_VELOCITY.getDefaultUnit();

	private final FlightLog simulated;
	private final FlightLogStats simulatedStats;
	private FlightLog flight;
	private FlightLogStats flightStats;

	private final XYSeries simAltitude = new XYSeries("sim-altitude", false);
	private final XYSeries simVelocity = new XYSeries("sim-velocity", false);
	private final XYSeries logAltitude = new XYSeries("log-altitude", false);
	private final XYSeries logVelocity = new XYSeries("log-velocity", false);
	private final XYPlot altitudePlot;
	private final XYPlot velocityPlot;

	private final JLabel fileLabel = new JLabel(trans.get("Wyvern.flightlog.lbl.noFile"));
	private final JLabel warningLabel = new JLabel(" ");
	private final JSpinner shiftSpinner = new JSpinner(new SpinnerNumberModel(0.0, -600.0, 600.0, 0.1));
	private final JButton alignButton = new JButton(trans.get("Wyvern.flightlog.but.align"));
	private final DefaultTableModel tableModel;

	/**
	 * Open the overlay for a simulation, or tell the user why it cannot be opened.
	 *
	 * @param owner       the parent window
	 * @param simulations the simulations selected in the simulation table
	 */
	public static void show(Window owner, Simulation[] simulations) {
		if (simulations == null || simulations.length != 1) {
			JOptionPane.showMessageDialog(owner, trans.get("Wyvern.flightlog.msg.selectOne"),
					trans.get("Wyvern.flightlog.title"), JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		FlightData data = simulations[0].getSimulatedData();
		if (data == null || data.getBranchCount() == 0) {
			JOptionPane.showMessageDialog(owner, trans.get("Wyvern.flightlog.msg.runFirst"),
					trans.get("Wyvern.flightlog.title"), JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		FlightLog simulated;
		try {
			simulated = SimulationLog.of(data.getBranch(0));
		} catch (IllegalArgumentException e) {
			JOptionPane.showMessageDialog(owner, trans.get("Wyvern.flightlog.msg.runFirst"),
					trans.get("Wyvern.flightlog.title"), JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		new FlightLogDialog(owner, simulations[0].getName(), simulated).setVisible(true);
	}

	private FlightLogDialog(Window owner, String simulationName, FlightLog simulated) {
		super(owner, trans.get("Wyvern.flightlog.title") + " - " + simulationName, ModalityType.MODELESS);
		this.simulated = simulated;
		this.simulatedStats = FlightLogStats.of(simulated);

		for (int i = 0; i < simulated.size(); i++) {
			simAltitude.add(simulated.getTime(i), distanceUnit.toUnit(simulated.getAltitude(i)), false);
			simVelocity.add(simulated.getTime(i), velocityUnit.toUnit(simulated.getVelocity(i)), false);
		}

		altitudePlot = plot(simAltitude, logAltitude,
				trans.get("Wyvern.flightlog.axis.altitude") + " (" + distanceUnit.getUnit() + ")");
		velocityPlot = plot(simVelocity, logVelocity,
				trans.get("Wyvern.flightlog.axis.velocity") + " (" + velocityUnit.getUnit() + ")");
		NumberAxis timeAxis = new NumberAxis(trans.get("Wyvern.flightlog.axis.time") + " (s)");
		timeAxis.setAutoRangeIncludesZero(false);
		style(timeAxis);
		CombinedDomainXYPlot combined = new CombinedDomainXYPlot(timeAxis);
		combined.setGap(10);
		combined.add(altitudePlot, 3);
		combined.add(velocityPlot, 2);
		JFreeChart chart = new JFreeChart(null, JFreeChart.DEFAULT_TITLE_FONT, combined, false);
		chart.setBackgroundPaint(UIManager.getColor("Panel.background"));
		ChartPanel chartPanel = new ChartPanel(chart);
		chartPanel.setPreferredSize(new Dimension(860, 380));
		chartPanel.setMouseWheelEnabled(true);

		tableModel = new DefaultTableModel(new String[] { trans.get("Wyvern.flightlog.col.metric"),
				trans.get("Wyvern.flightlog.col.simulation"), trans.get("Wyvern.flightlog.col.flight"),
				trans.get("Wyvern.flightlog.col.difference"), trans.get("Wyvern.flightlog.col.percent") }, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};
		JTable table = new JTable(tableModel);
		table.setFocusable(false);
		table.setRowSelectionAllowed(false);
		JScrollPane tableScroll = new JScrollPane(table);
		tableScroll.setPreferredSize(new Dimension(860, 9 * table.getRowHeight() + 8));

		JButton openButton = new JButton(trans.get("Wyvern.flightlog.but.open"));
		openButton.addActionListener(e -> chooseFile());
		shiftSpinner.setEditor(new JSpinner.NumberEditor(shiftSpinner, "0.00"));
		shiftSpinner.setToolTipText(trans.get("Wyvern.flightlog.lbl.shift.ttip"));
		shiftSpinner.addChangeListener(e -> refresh());
		alignButton.setToolTipText(trans.get("Wyvern.flightlog.but.align.ttip"));
		alignButton.addActionListener(e -> {
			if (flightStats != null) {
				double shift = simulatedStats.apogeeTime() - flightStats.apogeeTime();
				shiftSpinner.setValue(Math.round(shift * 100) / 100.0);
			}
		});
		alignButton.setEnabled(false);
		shiftSpinner.setEnabled(false);
		JButton closeButton = new JButton(trans.get("dlg.but.close"));
		closeButton.addActionListener(e -> dispose());

		JPanel panel = new JPanel(new MigLayout("fill, ins dialog", "[grow]", "[][grow][][][]"));
		panel.add(openButton, "split 6");
		panel.add(fileLabel, "growx, pushx, wmin 100");
		panel.add(new JLabel(trans.get("Wyvern.flightlog.lbl.shift")), "gapleft para");
		panel.add(shiftSpinner, "w 80!");
		panel.add(new JLabel("s"));
		panel.add(alignButton, "gapleft rel, wrap");
		panel.add(chartPanel, "grow, wrap");
		panel.add(new JLabel(legend()), "wrap");
		panel.add(tableScroll, "growx, wrap");
		panel.add(warningLabel, "split 2, growx, pushx, wmin 100");
		panel.add(closeButton, "right");
		setContentPane(panel);

		refresh();
		GUIUtil.setDisposableDialogOptions(this, closeButton);
		setLocationRelativeTo(owner);
	}

	private String legend() {
		return "<html><font color='#56B4E9'>\u2501\u2501</font> " + trans.get("Wyvern.flightlog.col.simulation")
				+ " &nbsp;&nbsp; <font color='#E69F00'>\u2505\u2505</font> " + trans.get("Wyvern.flightlog.col.flight")
				+ " (" + trans.get("Wyvern.flightlog.lbl.dashed") + ")</html>";
	}

	private static XYPlot plot(XYSeries simulation, XYSeries flight, String rangeLabel) {
		XYSeriesCollection data = new XYSeriesCollection();
		data.addSeries(simulation);
		data.addSeries(flight);
		XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
		// Draw each series as one path so the dash pattern runs along the line and survives dense samples
		renderer.setDrawSeriesLineAsPath(true);
		renderer.setSeriesPaint(0, SIMULATION_COLOR);
		renderer.setSeriesStroke(0, SIMULATION_STROKE);
		renderer.setSeriesPaint(1, FLIGHT_COLOR);
		renderer.setSeriesStroke(1, FLIGHT_STROKE);
		NumberAxis axis = new NumberAxis(rangeLabel);
		axis.setAutoRangeIncludesZero(true);
		style(axis);
		XYPlot plot = new XYPlot(data, null, axis, renderer);
		Color background = UIManager.getColor("TextField.background");
		Color grid = UIManager.getColor("Separator.foreground");
		plot.setBackgroundPaint(background != null ? background : Color.WHITE);
		plot.setDomainGridlinePaint(grid != null ? grid : Color.GRAY);
		plot.setRangeGridlinePaint(grid != null ? grid : Color.GRAY);
		return plot;
	}

	private static void style(NumberAxis axis) {
		Color text = UIManager.getColor("Label.foreground");
		if (text != null) {
			axis.setLabelPaint(text);
			axis.setTickLabelPaint(text);
			axis.setAxisLinePaint(text);
			axis.setTickMarkPaint(text);
		}
	}

	////////  Loading  ////////

	private void chooseFile() {
		JFileChooser chooser = new JFileChooser(Application.getPreferences().getDefaultDirectory());
		chooser.setFileFilter(new FileNameExtensionFilter(trans.get("Wyvern.flightlog.filter"), "csv", "txt"));
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		load(chooser.getSelectedFile());
	}

	/** Load a log file and show it; problems are reported to the user and leave the current log in place. */
	public void load(File file) {
		try {
			FlightLog log = read(file, null);
			if (log == null) {
				return;
			}
			flight = log;
			flightStats = FlightLogStats.of(log);
			fileLabel.setText(file.getName() + " (" + log.getSource() + ")");
			warningLabel.setText(log.getWarnings().isEmpty() ? " "
					: "<html>" + String.join("<br>", log.getWarnings()) + "</html>");
			alignButton.setEnabled(true);
			shiftSpinner.setEnabled(true);
			shiftSpinner.setValue(0.0);
			refresh();
		} catch (IOException | IllegalArgumentException e) {
			JOptionPane.showMessageDialog(this,
					String.format(trans.get("Wyvern.flightlog.msg.cannotRead"), file.getName(), e.getMessage()),
					trans.get("Wyvern.flightlog.title"), JOptionPane.ERROR_MESSAGE);
		}
	}

	private FlightLog read(File file, ColumnMapping mapping) throws IOException {
		String header;
		try (BufferedReader in = new BufferedReader(open(file))) {
			header = in.readLine();
			while (header != null && header.isBlank()) {
				header = in.readLine();
			}
		}
		if (header == null) {
			throw new IllegalArgumentException(trans.get("Wyvern.flightlog.msg.empty"));
		}
		ColumnMapping use = mapping;
		if (FlightLogReader.detect(header) == FlightLogReader.Format.GENERIC) {
			use = askMapping(FlightLogReader.cells(header), FlightLogReader.guessMapping(header));
			if (use == null) {
				return null;
			}
		}
		try (Reader in = open(file)) {
			return FlightLogReader.read(in, use);
		}
	}

	/** Altimeter exports are not always clean UTF-8; a decoding reader replaces bad bytes where a strict one fails. */
	private static Reader open(File file) throws IOException {
		return new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8);
	}

	/** Ask which columns of an unrecognized CSV hold time, altitude and velocity. Returns null if cancelled. */
	private ColumnMapping askMapping(List<String> columns, ColumnMapping guess) {
		String none = trans.get("Wyvern.flightlog.map.none");
		String[] names = columns.toArray(new String[0]);
		String[] optional = new String[names.length + 1];
		optional[0] = none;
		System.arraycopy(names, 0, optional, 1, names.length);
		String[] altitudeUnits = { "m", "ft" };
		String[] velocityUnits = { "m/s", "ft/s", "km/h", "mph" };
		double[] velocityScales = { 1.0, 0.3048, 1 / 3.6, 0.44704 };

		JComboBox<String> time = new JComboBox<>(names);
		JComboBox<String> altitude = new JComboBox<>(names);
		JComboBox<String> altitudeUnit = new JComboBox<>(altitudeUnits);
		JComboBox<String> velocity = new JComboBox<>(optional);
		JComboBox<String> velocityUnit = new JComboBox<>(velocityUnits);
		if (guess != null) {
			time.setSelectedIndex(guess.timeColumn());
			altitude.setSelectedIndex(guess.altitudeColumn());
			altitudeUnit.setSelectedIndex(guess.altitudeScale() == 1.0 ? 0 : 1);
			velocity.setSelectedIndex(guess.velocityColumn() + 1);
			velocityUnit.setSelectedIndex(guess.velocityScale() == 1.0 ? 0 : 1);
		} else if (names.length > 1) {
			altitude.setSelectedIndex(1);
		}
		JPanel form = new JPanel(new MigLayout("ins 0", "[][grow][]"));
		form.add(new JLabel(trans.get("Wyvern.flightlog.map.intro")), "span, wrap para");
		form.add(new JLabel(trans.get("Wyvern.flightlog.map.time")));
		form.add(time, "growx");
		form.add(new JLabel("s"), "wrap");
		form.add(new JLabel(trans.get("Wyvern.flightlog.map.altitude")));
		form.add(altitude, "growx");
		form.add(altitudeUnit, "wrap");
		form.add(new JLabel(trans.get("Wyvern.flightlog.map.velocity")));
		form.add(velocity, "growx");
		form.add(velocityUnit, "wrap");
		if (JOptionPane.showConfirmDialog(this, form, trans.get("Wyvern.flightlog.map.title"),
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
			return null;
		}
		return new ColumnMapping(time.getSelectedIndex(), altitude.getSelectedIndex(),
				altitudeUnit.getSelectedIndex() == 0 ? 1.0 : 0.3048, velocity.getSelectedIndex() - 1,
				velocityScales[velocityUnit.getSelectedIndex()]);
	}

	////////  Display  ////////

	private void refresh() {
		double shift = ((Number) shiftSpinner.getValue()).doubleValue();
		logAltitude.clear();
		logVelocity.clear();
		altitudePlot.clearDomainMarkers();
		if (flight != null) {
			double[] velocity = FlightLogStats.velocity(flight);
			List<double[]> gaps = flight.gaps();
			for (int i = 0; i < flight.size(); i++) {
				double t = flight.getTime(i);
				for (double[] gap : gaps) {
					if (gap[1] == t) {
						// A hole in the series stops the line being drawn straight across missing data
						logAltitude.add(gap[0] + shift + 1e-6, Double.NaN, false);
						logVelocity.add(gap[0] + shift + 1e-6, Double.NaN, false);
					}
				}
				logAltitude.add(t + shift, distanceUnit.toUnit(flight.getAltitude(i)), false);
				logVelocity.add(t + shift, Double.isNaN(velocity[i]) ? Double.NaN : velocityUnit.toUnit(velocity[i]),
						false);
			}
			Color text = UIManager.getColor("Label.foreground");
			for (FlightLog.Event event : flight.getEvents()) {
				if (event.type() != FlightLog.EventType.DROGUE && event.type() != FlightLog.EventType.MAIN) {
					continue;
				}
				ValueMarker marker = new ValueMarker(event.time() + shift);
				marker.setPaint(FLIGHT_COLOR);
				marker.setStroke(new BasicStroke(1f));
				marker.setLabel(trans.get(event.type() == FlightLog.EventType.DROGUE ? "Wyvern.flightlog.event.drogue"
						: "Wyvern.flightlog.event.main"));
				marker.setLabelPaint(text != null ? text : Color.BLACK);
				marker.setLabelAnchor(RectangleAnchor.TOP_RIGHT);
				marker.setLabelTextAnchor(TextAnchor.TOP_LEFT);
				altitudePlot.addDomainMarker(marker);
			}
		}
		logAltitude.fireSeriesChanged();
		logVelocity.fireSeriesChanged();

		tableModel.setRowCount(0);
		FlightLogStats f = flightStats;
		addRow("Wyvern.flightlog.row.apogee", simulatedStats.apogee(), f == null ? Double.NaN : f.apogee(),
				distanceUnit);
		addRow("Wyvern.flightlog.row.apogeeTime", simulatedStats.apogeeTime(),
				f == null ? Double.NaN : f.apogeeTime() + shift, null);
		addRow("Wyvern.flightlog.row.maxVelocity", simulatedStats.maxVelocity(),
				f == null ? Double.NaN : f.maxVelocity(), velocityUnit);
		addRow("Wyvern.flightlog.row.drogueRate", simulatedStats.drogueRate(), f == null ? Double.NaN : f.drogueRate(),
				velocityUnit);
		addRow("Wyvern.flightlog.row.mainAltitude", simulatedStats.mainAltitude(),
				f == null ? Double.NaN : f.mainAltitude(), distanceUnit);
		addRow("Wyvern.flightlog.row.mainRate", simulatedStats.mainRate(), f == null ? Double.NaN : f.mainRate(),
				velocityUnit);
		addRow("Wyvern.flightlog.row.descentTime", simulatedStats.descentTime(),
				f == null ? Double.NaN : f.descentTime(), null);
	}

	/** Add a comparison row; a null unit means the values are times in seconds. */
	private void addRow(String key, double simulation, double measured, Unit unit) {
		String difference = "";
		String percent = "";
		if (!Double.isNaN(simulation) && !Double.isNaN(measured)) {
			double delta = measured - simulation;
			if (Math.abs(delta) < 1e-6 * Math.max(1, Math.abs(simulation))) {
				delta = 0;
			}
			difference = (delta > 0 ? "+" : delta < 0 ? "\u2212" : "") + format(Math.abs(delta), unit);
			if (Math.abs(simulation) > 1e-9) {
				percent = String.format(Locale.ROOT, "%+.1f%%", 100 * delta / simulation);
			}
		}
		tableModel.addRow(new Object[] { trans.get(key), format(simulation, unit), format(measured, unit), difference,
				percent });
	}

	private static String format(double value, Unit unit) {
		if (Double.isNaN(value)) {
			return "-";
		}
		return unit == null ? String.format(Locale.ROOT, "%.2f s", value) : unit.toStringUnit(value);
	}
}
