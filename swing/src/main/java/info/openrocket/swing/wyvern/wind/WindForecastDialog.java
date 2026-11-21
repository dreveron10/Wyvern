package info.openrocket.swing.wyvern.wind;

import java.awt.Dimension;
import java.awt.Window;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

import net.miginfocom.swing.MigLayout;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.Unit;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.wyvern.wind.OpenMeteo;
import info.openrocket.core.wyvern.wind.WindForecast;
import info.openrocket.core.wyvern.wind.WindForecast.Hour;
import info.openrocket.core.wyvern.wind.WindForecast.Level;
import info.openrocket.swing.gui.util.GUIUtil;

/**
 * Downloads the forecast wind at a simulation's launch site, shows it by height for a
 * chosen hour, and writes that hour into the selected simulations as a multi-level wind.
 */
public class WindForecastDialog extends JDialog {
	private static final Translator trans = Application.getTranslator();
	private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm");
	private static final int FORECAST_DAYS = 7;

	private final Simulation[] simulations;
	private final double latitude;
	private final double longitude;
	private final Unit distanceUnit = UnitGroup.UNITS_DISTANCE.getDefaultUnit();
	private final Unit speedUnit = UnitGroup.UNITS_WINDSPEED.getDefaultUnit();

	private WindForecast forecast;
	private final JLabel statusLabel = new JLabel(trans.get("Wyvern.wind.lbl.none"));
	private final JLabel gustLabel = new JLabel(" ");
	private final JComboBox<String> hourBox = new JComboBox<>();
	private final DefaultTableModel tableModel;
	private final JButton fetchButton = new JButton(trans.get("Wyvern.wind.but.fetch"));
	private final JButton applyButton = new JButton(trans.get("Wyvern.wind.but.apply"));
	private final JSpinner turbulenceSpinner;

	/**
	 * Open the dialog for the selected simulations, or tell the user to select some.
	 *
	 * @param owner       the parent window
	 * @param simulations the simulations selected in the simulation table
	 */
	public static void show(Window owner, Simulation[] simulations) {
		if (simulations == null || simulations.length == 0) {
			JOptionPane.showMessageDialog(owner, trans.get("Wyvern.wind.msg.select"), trans.get("Wyvern.wind.title"),
					JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		new WindForecastDialog(owner, simulations).setVisible(true);
	}

	private WindForecastDialog(Window owner, Simulation[] simulations) {
		super(owner, trans.get("Wyvern.wind.title"), ModalityType.DOCUMENT_MODAL);
		this.simulations = simulations;
		this.latitude = simulations[0].getOptions().getLaunchLatitude();
		this.longitude = simulations[0].getOptions().getLaunchLongitude();

		tableModel = new DefaultTableModel(new String[] {
				trans.get("Wyvern.wind.col.altitude") + " (" + distanceUnit.getUnit() + ")",
				trans.get("Wyvern.wind.col.speed") + " (" + speedUnit.getUnit() + ")",
				trans.get("Wyvern.wind.col.direction") }, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};
		JTable table = new JTable(tableModel);
		table.setFocusable(false);
		table.setRowSelectionAllowed(false);
		JScrollPane scroll = new JScrollPane(table);
		scroll.setPreferredSize(new Dimension(520, 15 * table.getRowHeight()));

		double defaultIntensity = Application.getPreferences().getAverageWindModel().getTurbulenceIntensity();
		turbulenceSpinner = new JSpinner(new SpinnerNumberModel(
				Math.round(Math.max(0, Math.min(1, defaultIntensity)) * 1000) / 10.0, 0.0, 100.0, 1.0));
		turbulenceSpinner.setToolTipText(trans.get("Wyvern.wind.lbl.turbulence.ttip"));

		fetchButton.addActionListener(e -> fetch());
		hourBox.addActionListener(e -> showHour());
		hourBox.setEnabled(false);
		applyButton.setToolTipText(trans.get("Wyvern.wind.but.apply.ttip"));
		applyButton.setEnabled(false);
		applyButton.addActionListener(e -> apply());
		JButton closeButton = new JButton(trans.get("dlg.but.close"));
		closeButton.addActionListener(e -> dispose());

		JPanel panel = new JPanel(new MigLayout("fill, ins dialog", "[][grow]", "[][][][][grow][][]"));
		panel.add(new JLabel(trans.get("Wyvern.wind.lbl.site")));
		panel.add(new JLabel(formatPosition(latitude, longitude)), "split 2, growx");
		panel.add(fetchButton, "right, wrap");
		JLabel hint = new JLabel(trans.get("Wyvern.wind.lbl.siteHint"));
		hint.setEnabled(false);
		panel.add(hint, "skip 1, wrap");
		panel.add(statusLabel, "span, growx, wmin 100, wrap para");
		panel.add(new JLabel(trans.get("Wyvern.wind.lbl.hour")));
		panel.add(hourBox, "split 2, w 220!");
		panel.add(gustLabel, "gapleft para, wrap");
		panel.add(scroll, "span, grow, wrap");
		JLabel turbulenceLabel = new JLabel(trans.get("Wyvern.wind.lbl.turbulence"));
		turbulenceLabel.setToolTipText(trans.get("Wyvern.wind.lbl.turbulence.ttip"));
		panel.add(turbulenceLabel);
		panel.add(turbulenceSpinner, "split 2, w 70!");
		panel.add(new JLabel("%"), "wrap para");
		JLabel attribution = new JLabel(trans.get("Wyvern.wind.lbl.attribution"));
		attribution.setEnabled(false);
		panel.add(attribution, "span, split 3, growx, pushx");
		panel.add(applyButton);
		panel.add(closeButton);
		setContentPane(panel);

		GUIUtil.setDisposableDialogOptions(this, closeButton);
		setLocationRelativeTo(owner);
	}

	static String formatPosition(double latitude, double longitude) {
		return String.format(Locale.ROOT, "%.4f\u00B0 %s, %.4f\u00B0 %s", Math.abs(latitude), latitude >= 0 ? "N" : "S",
				Math.abs(longitude), longitude >= 0 ? "E" : "W");
	}

	private void fetch() {
		fetchButton.setEnabled(false);
		statusLabel.setText(trans.get("Wyvern.wind.lbl.fetching"));
		new SwingWorker<WindForecast, Void>() {
			@Override
			protected WindForecast doInBackground() throws Exception {
				return OpenMeteo.fetch(latitude, longitude, FORECAST_DAYS);
			}

			@Override
			protected void done() {
				fetchButton.setEnabled(true);
				try {
					setForecast(get());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} catch (ExecutionException e) {
					Throwable cause = e.getCause() != null ? e.getCause() : e;
					String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
					statusLabel.setText(forecast == null ? trans.get("Wyvern.wind.lbl.none") : statusLabel.getText());
					JOptionPane.showMessageDialog(WindForecastDialog.this,
							String.format(trans.get("Wyvern.wind.msg.failed"), reason), trans.get("Wyvern.wind.title"),
							JOptionPane.ERROR_MESSAGE);
					if (forecast != null) {
						describeForecast();
					}
				}
			}
		}.execute();
	}

	private void setForecast(WindForecast loaded) {
		forecast = loaded;
		describeForecast();
		DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
		for (Hour hour : loaded.hours()) {
			model.addElement(hour.time().format(HOUR_FORMAT));
		}
		hourBox.setModel(model);
		hourBox.setEnabled(true);
		applyButton.setEnabled(true);
		// Start at the hour closest to now at the site
		LocalDateTime now;
		try {
			now = LocalDateTime.now(ZoneId.of(loaded.timezone()));
		} catch (RuntimeException e) {
			now = LocalDateTime.now();
		}
		hourBox.setSelectedIndex(loaded.hours().indexOf(loaded.closest(now)));
		showHour();
	}

	private void describeForecast() {
		statusLabel.setText("<html>" + String.format(trans.get("Wyvern.wind.lbl.loaded"),
				formatPosition(forecast.latitude(), forecast.longitude()),
				distanceUnit.toStringUnit(forecast.elevation()), forecast.timezone()) + "</html>");
	}

	private Hour selectedHour() {
		int index = hourBox.getSelectedIndex();
		return forecast == null || index < 0 ? null : forecast.hours().get(index);
	}

	private void showHour() {
		tableModel.setRowCount(0);
		Hour hour = selectedHour();
		if (hour == null) {
			gustLabel.setText(" ");
			return;
		}
		gustLabel.setText(Double.isNaN(hour.gust()) ? " "
				: String.format(trans.get("Wyvern.wind.lbl.gust"), speedUnit.toStringUnit(hour.gust())));
		for (Level level : hour.levels()) {
			tableModel.addRow(new Object[] { distanceUnit.toString(level.altitude()), speedUnit.toString(level.speed()),
					String.format(Locale.ROOT, "%.0f\u00B0 %s", Math.toDegrees(level.direction()),
							compass(level.direction())) });
		}
	}

	/** Sixteen-point compass name of a direction in rad clockwise from north. */
	static String compass(double direction) {
		String[] names = { "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW",
				"NNW" };
		double degrees = ((Math.toDegrees(direction) % 360) + 360) % 360;
		return names[(int) Math.round(degrees / 22.5) % 16];
	}

	private void apply() {
		Hour hour = selectedHour();
		if (hour == null) {
			return;
		}
		double intensity = ((Number) turbulenceSpinner.getValue()).doubleValue() / 100.0;
		for (Simulation simulation : simulations) {
			WindForecast.apply(hour, simulation.getOptions(), intensity);
		}
		JOptionPane.showMessageDialog(this,
				String.format(trans.get("Wyvern.wind.msg.applied"), hour.time().format(HOUR_FORMAT), simulations.length),
				trans.get("Wyvern.wind.title"), JOptionPane.INFORMATION_MESSAGE);
	}
}
