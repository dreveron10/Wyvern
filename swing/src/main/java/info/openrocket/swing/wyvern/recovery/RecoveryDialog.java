package info.openrocket.swing.wyvern.recovery;

import java.awt.Dialog;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;

import net.miginfocom.swing.MigLayout;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.Unit;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.wyvern.recovery.RecoveryAnalyzer;
import info.openrocket.core.wyvern.recovery.RecoveryReport;
import info.openrocket.core.wyvern.recovery.RecoverySetup;
import info.openrocket.swing.gui.components.StyledLabel;
import info.openrocket.swing.gui.util.GUIUtil;
import info.openrocket.swing.wyvern.ui.RowTableModel;
import info.openrocket.swing.wyvern.ui.RowTableModel.Column;

/**
 * Recovery analysis of one simulation: descent phases and opening forces, section
 * kinetic energies, hardware margins, ejection charges and pad stay time.
 * <p>
 * Results come from {@link RecoveryAnalyzer}. Everything the user enters is kept in a
 * {@link RecoverySetup} stored with the design, and is only written to the document
 * once the user changes something.
 */
public class RecoveryDialog extends JDialog {
	private static final Translator trans = Application.getTranslator();

	private final OpenRocketDocument document;
	private final Simulation simulation;
	private final RecoverySetup setup;
	private RecoveryReport report;
	private int branchIndex = 0;

	private final Unit lengthUnit = UnitGroup.UNITS_LENGTH.getDefaultUnit();
	private final Unit distanceUnit = UnitGroup.UNITS_DISTANCE.getDefaultUnit();
	private final Unit velocityUnit = UnitGroup.UNITS_VELOCITY.getDefaultUnit();
	private final Unit massUnit = UnitGroup.UNITS_MASS.getDefaultUnit();
	private final Unit forceUnit = UnitGroup.UNITS_FORCE.getDefaultUnit();
	private final Unit energyUnit = UnitGroup.UNITS_ENERGY.getDefaultUnit();
	private final Unit pressureUnit = UnitGroup.UNITS_PRESSURE.getDefaultUnit();
	private final Unit timeUnit = UnitGroup.UNITS_SHORT_TIME.getDefaultUnit();
	private final Unit gramUnit = unitNamed(UnitGroup.UNITS_MASS, "g");

	private final JLabel summaryLabel = new JLabel();
	private final JLabel noteLabel = new StyledLabel(-1f);
	private final JLabel loadLabel = new JLabel();
	private final JLabel padStayLabel = new JLabel();

	private final RowTableModel<RecoveryReport.Phase> phaseModel;
	private final RowTableModel<Integer> sectionModel;
	private final RowTableModel<Integer> hardwareModel;
	private final RowTableModel<Integer> chargeModel;
	private final RowTableModel<Integer> batteryModel;
	private int sectionPhaseColumns = -1;

	/**
	 * Show the recovery analysis for a simulation, or explain why it cannot be shown.
	 *
	 * @param owner       parent window
	 * @param document    the open document
	 * @param simulations the simulations currently selected by the user
	 */
	public static void show(Window owner, OpenRocketDocument document, Simulation[] simulations) {
		if (simulations == null || simulations.length != 1) {
			JOptionPane.showMessageDialog(owner, trans.get("Wyvern.recovery.msg.selectOne"),
					trans.get("Wyvern.recovery.title"), JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		Simulation sim = simulations[0];
		FlightData data = sim.getSimulatedData();
		if (data == null || data.getBranchCount() == 0 || data.getBranch(0).getLength() < 2) {
			JOptionPane.showMessageDialog(owner, trans.get("Wyvern.recovery.msg.noData"),
					trans.get("Wyvern.recovery.title"), JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		new RecoveryDialog(owner, document, sim).setVisible(true);
	}

	public RecoveryDialog(Window owner, OpenRocketDocument document, Simulation simulation) {
		super(owner, trans.get("Wyvern.recovery.title") + " - " + simulation.getName(),
				Dialog.ModalityType.DOCUMENT_MODAL);
		this.document = document;
		this.simulation = simulation;
		this.setup = RecoverySetup.load(document);
		if (setup.sections.isEmpty()) {
			setup.sections.add(wholeRocketSection());
		}

		phaseModel = new RowTableModel<>(() -> report == null ? List.of() : report.phases(), null);
		sectionModel = new RowTableModel<>(indices(() -> setup.sections), this::edited);
		hardwareModel = new RowTableModel<>(indices(() -> setup.hardware), this::edited);
		chargeModel = new RowTableModel<>(indices(() -> setup.chargeBays), this::edited);
		batteryModel = new RowTableModel<>(indices(() -> setup.batteries), this::edited);

		JPanel panel = new JPanel(new MigLayout("fill, ins dialog"));

		FlightData data = simulation.getSimulatedData();
		if (data.getBranchCount() > 1) {
			JComboBox<String> branches = new JComboBox<>();
			for (int i = 0; i < data.getBranchCount(); i++) {
				branches.addItem(data.getBranch(i).getName());
			}
			branches.addActionListener(e -> {
				branchIndex = branches.getSelectedIndex();
				refresh();
			});
			panel.add(new JLabel(trans.get("Wyvern.recovery.lbl.body")), "split 2");
			panel.add(branches, "wrap");
		}
		panel.add(summaryLabel, "growx, wrap");
		panel.add(noteLabel, "growx, wrap para");

		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab(trans.get("Wyvern.recovery.tab.descent"), descentTab());
		tabs.addTab(trans.get("Wyvern.recovery.tab.sections"), sectionsTab());
		tabs.addTab(trans.get("Wyvern.recovery.tab.hardware"), hardwareTab());
		tabs.addTab(trans.get("Wyvern.recovery.tab.charges"), chargesTab());
		tabs.addTab(trans.get("Wyvern.recovery.tab.batteries"), batteriesTab());
		panel.add(tabs, "grow, push, wrap para");

		JButton close = new JButton(trans.get("dlg.but.close"));
		close.addActionListener(e -> dispose());
		panel.add(close, "right");

		setContentPane(panel);
		setColumns();
		refresh();
		GUIUtil.setDisposableDialogOptions(this, close);
		setSize(Math.max(getWidth(), 980), Math.max(getHeight(), 560));
		setLocationRelativeTo(owner);
	}

	////////  Tabs  ////////

	private JPanel descentTab() {
		JPanel tab = new JPanel(new MigLayout("fill"));
		tab.add(new StyledLabel(trans.get("Wyvern.recovery.descent.help"), -1f), "growx, wrap");
		JTable table = RowTableModel.createTable(phaseModel);
		tab.add(new JScrollPane(table), "grow, push");
		phaseModel.addTableModelListener(e -> {
			if (table.getColumnCount() > 0) {
				table.getColumnModel().getColumn(0).setPreferredWidth(260);
			}
		});
		return tab;
	}

	private JPanel sectionsTab() {
		JPanel tab = new JPanel(new MigLayout("fill"));
		tab.add(new StyledLabel(trans.get("Wyvern.recovery.sections.help"), -1f), "growx, wrap");
		JTable table = RowTableModel.createTable(sectionModel);
		tab.add(new JScrollPane(table), "grow, push, wrap");

		JButton add = new JButton(trans.get("Wyvern.recovery.but.addSection"));
		add.addActionListener(e -> {
			setup.sections.add(new RecoverySetup.Section(
					trans.get("Wyvern.recovery.section.default") + " " + (setup.sections.size() + 1)));
			edited();
		});
		JButton components = new JButton(trans.get("Wyvern.recovery.but.components"));
		components.addActionListener(e -> {
			int row = table.getSelectedRow();
			if (row < 0 && setup.sections.size() == 1) {
				row = 0;
			}
			if (row >= 0 && chooseComponents(setup.sections.get(row))) {
				edited();
			}
		});
		tab.add(add, "split 3");
		tab.add(components);
		tab.add(removeButton(table, () -> setup.sections));
		return tab;
	}

	private JPanel hardwareTab() {
		JPanel tab = new JPanel(new MigLayout("fill"));
		tab.add(new StyledLabel(trans.get("Wyvern.recovery.hardware.help"), -1f), "growx, wrap");

		JSpinner cx = spinner(setup.openingForceCoeff, 0.1, 5, 0.05);
		cx.addChangeListener(e -> {
			setup.openingForceCoeff = (Double) cx.getValue();
			edited();
		});
		JSpinner x1 = spinner(setup.forceReductionFactor, 0.01, 1, 0.01);
		x1.addChangeListener(e -> {
			setup.forceReductionFactor = (Double) x1.getValue();
			edited();
		});
		JSpinner sf = spinner(setup.safetyFactor, 1, 10, 0.1);
		sf.addChangeListener(e -> {
			setup.safetyFactor = (Double) sf.getValue();
			edited();
		});
		JPanel inputs = new JPanel(new MigLayout("ins 0"));
		inputs.add(new JLabel(trans.get("Wyvern.recovery.lbl.cx")));
		inputs.add(cx, "w 70lp, gapright para");
		inputs.add(new JLabel(trans.get("Wyvern.recovery.lbl.x1")));
		inputs.add(x1, "w 70lp, gapright para");
		inputs.add(new JLabel(trans.get("Wyvern.recovery.lbl.safetyFactor")));
		inputs.add(sf, "w 70lp");
		tab.add(inputs, "wrap");
		tab.add(loadLabel, "growx, wrap");

		JTable table = RowTableModel.createTable(hardwareModel);
		tab.add(new JScrollPane(table), "grow, push, wrap");
		JButton add = new JButton(trans.get("Wyvern.recovery.but.addHardware"));
		add.addActionListener(e -> {
			setup.hardware.add(new RecoverySetup.HardwareItem(trans.get("Wyvern.recovery.hardware.default"), 0));
			edited();
		});
		tab.add(add, "split 2");
		tab.add(removeButton(table, () -> setup.hardware));
		return tab;
	}

	private JPanel chargesTab() {
		JPanel tab = new JPanel(new MigLayout("fill"));
		tab.add(new StyledLabel(trans.get("Wyvern.recovery.charges.help"), -1f), "growx, wrap");
		JTable table = RowTableModel.createTable(chargeModel);
		tab.add(new JScrollPane(table), "grow, push, wrap");
		JButton add = new JButton(trans.get("Wyvern.recovery.but.addBay"));
		add.addActionListener(e -> {
			setup.chargeBays.add(new RecoverySetup.ChargeBay(
					trans.get("Wyvern.recovery.bay.default") + " " + (setup.chargeBays.size() + 1),
					largestInnerDiameter(), 0));
			edited();
		});
		tab.add(add, "split 2");
		tab.add(removeButton(table, () -> setup.chargeBays));
		return tab;
	}

	private JPanel batteriesTab() {
		JPanel tab = new JPanel(new MigLayout("fill"));
		tab.add(new StyledLabel(trans.get("Wyvern.recovery.batteries.help"), -1f), "growx, wrap");
		tab.add(padStayLabel, "growx, wrap");
		JTable table = RowTableModel.createTable(batteryModel);
		tab.add(new JScrollPane(table), "grow, push, wrap");
		JButton add = new JButton(trans.get("Wyvern.recovery.but.addBattery"));
		add.addActionListener(e -> {
			setup.batteries.add(new RecoverySetup.Battery(
					trans.get("Wyvern.recovery.battery.default") + " " + (setup.batteries.size() + 1), 0, 0));
			edited();
		});
		tab.add(add, "split 2");
		tab.add(removeButton(table, () -> setup.batteries));
		return tab;
	}

	////////  Columns  ////////

	private void setColumns() {
		phaseModel.setColumns(List.of(
				RowTableModel.<RecoveryReport.Phase>text(trans.get("Wyvern.recovery.col.device"),
						p -> p.drogue() ? p.deviceName() + " (" + trans.get("Wyvern.recovery.drogue") + ")" : p.deviceName(), null),
				RowTableModel.number(trans.get("Wyvern.recovery.col.deployTime"), timeUnit, RecoveryReport.Phase::deployTime, null),
				RowTableModel.number(trans.get("Wyvern.recovery.col.deployAltitude"), distanceUnit, RecoveryReport.Phase::deployAltitude, null),
				RowTableModel.number(trans.get("Wyvern.recovery.col.deployVelocity"), velocityUnit, RecoveryReport.Phase::deployVelocity, null),
				RowTableModel.number(trans.get("Wyvern.recovery.col.openingForce"), forceUnit, RecoveryReport.Phase::openingForce, null),
				RowTableModel.number(trans.get("Wyvern.recovery.col.descentRate"), velocityUnit, RecoveryReport.Phase::endVelocity, null),
				RowTableModel.number(trans.get("Wyvern.recovery.col.terminalVelocity"), velocityUnit, RecoveryReport.Phase::terminalVelocity, null),
				RowTableModel.number(trans.get("Wyvern.recovery.col.duration"), timeUnit, RecoveryReport.Phase::duration, null)));

		hardwareModel.setColumns(List.of(
				RowTableModel.<Integer>text(trans.get("Wyvern.recovery.col.name"),
						i -> setup.hardware.get(i).name, (i, v) -> setup.hardware.get(i).name = v),
				RowTableModel.number(trans.get("Wyvern.recovery.col.ratedLoad"), forceUnit,
						i -> setup.hardware.get(i).ratedLoad, (i, v) -> setup.hardware.get(i).ratedLoad = Math.max(v, 0)),
				RowTableModel.number(trans.get("Wyvern.recovery.col.loadPercent"), null,
						i -> 100 * report.hardware().get(i).loadFraction(), null)));

		chargeModel.setColumns(List.of(
				RowTableModel.<Integer>text(trans.get("Wyvern.recovery.col.name"),
						i -> setup.chargeBays.get(i).name, (i, v) -> setup.chargeBays.get(i).name = v),
				RowTableModel.number(trans.get("Wyvern.recovery.col.diameter"), lengthUnit,
						i -> setup.chargeBays.get(i).diameter, (i, v) -> setup.chargeBays.get(i).diameter = Math.max(v, 0)),
				RowTableModel.number(trans.get("Wyvern.recovery.col.length"), lengthUnit,
						i -> setup.chargeBays.get(i).length, (i, v) -> setup.chargeBays.get(i).length = Math.max(v, 0)),
				RowTableModel.number(trans.get("Wyvern.recovery.col.pressure"), pressureUnit,
						i -> setup.chargeBays.get(i).pressure, (i, v) -> setup.chargeBays.get(i).pressure = Math.max(v, 0)),
				RowTableModel.number(trans.get("Wyvern.recovery.col.backupPercent"), null,
						i -> 100 * setup.chargeBays.get(i).backupIncrease,
						(i, v) -> setup.chargeBays.get(i).backupIncrease = Math.max(v, 0) / 100),
				RowTableModel.<Integer>integer(trans.get("Wyvern.recovery.col.pins"),
						i -> setup.chargeBays.get(i).shearPinCount,
						(i, v) -> setup.chargeBays.get(i).shearPinCount = Math.max(v, 0)),
				RowTableModel.number(trans.get("Wyvern.recovery.col.pinStrength"), forceUnit,
						i -> setup.chargeBays.get(i).shearPinStrength,
						(i, v) -> setup.chargeBays.get(i).shearPinStrength = Math.max(v, 0)),
				RowTableModel.number(trans.get("Wyvern.recovery.col.primary"), gramUnit,
						i -> report.charges().get(i).primaryMass(), null),
				RowTableModel.number(trans.get("Wyvern.recovery.col.backup"), gramUnit,
						i -> report.charges().get(i).backupMass(), null),
				RowTableModel.number(trans.get("Wyvern.recovery.col.separationForce"), forceUnit,
						i -> report.charges().get(i).separationForce(), null),
				RowTableModel.number(trans.get("Wyvern.recovery.col.pinFactor"), null,
						i -> report.charges().get(i).shearPinFactor(), null)));

		batteryModel.setColumns(List.of(
				RowTableModel.<Integer>text(trans.get("Wyvern.recovery.col.name"),
						i -> setup.batteries.get(i).name, (i, v) -> setup.batteries.get(i).name = v),
				RowTableModel.number(trans.get("Wyvern.recovery.col.capacity"), null,
						i -> 1000 * setup.batteries.get(i).capacity, (i, v) -> setup.batteries.get(i).capacity = Math.max(v, 0) / 1000),
				RowTableModel.number(trans.get("Wyvern.recovery.col.current"), null,
						i -> 1000 * setup.batteries.get(i).current, (i, v) -> setup.batteries.get(i).current = Math.max(v, 0) / 1000),
				RowTableModel.number(trans.get("Wyvern.recovery.col.usablePercent"), null,
						i -> 100 * setup.batteries.get(i).usableFraction,
						(i, v) -> setup.batteries.get(i).usableFraction = Math.min(Math.max(v, 0), 100) / 100),
				RowTableModel.number(trans.get("Wyvern.recovery.col.endurance"), null,
						i -> report.batteries().get(i).endurance() / 3600, null)));
	}

	/** The section table has one kinetic energy column per descent phase, so it follows the report. */
	private void setSectionColumns() {
		List<Column<Integer>> columns = new ArrayList<>();
		columns.add(RowTableModel.<Integer>text(trans.get("Wyvern.recovery.col.name"),
				i -> setup.sections.get(i).name, (i, v) -> setup.sections.get(i).name = v));
		columns.add(RowTableModel.<Integer>text(trans.get("Wyvern.recovery.col.components"),
				i -> componentNames(setup.sections.get(i)), null));
		columns.add(RowTableModel.number(trans.get("Wyvern.recovery.col.extraMass"), massUnit,
				i -> setup.sections.get(i).extraMass, (i, v) -> setup.sections.get(i).extraMass = Math.max(v, 0)));
		columns.add(RowTableModel.number(trans.get("Wyvern.recovery.col.mass"), massUnit,
				i -> report.sections().get(i).mass(), null));
		for (int p = 0; p < report.phases().size(); p++) {
			final int phase = p;
			RecoveryReport.Phase ph = report.phases().get(p);
			String title;
			if (p == report.phases().size() - 1) {
				title = trans.get("Wyvern.recovery.col.landingEnergy");
			} else if (ph.drogue()) {
				title = trans.get("Wyvern.recovery.col.energy") + " " + trans.get("Wyvern.recovery.drogue");
			} else {
				title = trans.get("Wyvern.recovery.col.energy") + " " + ph.deviceName();
			}
			columns.add(RowTableModel.number(title, energyUnit,
					i -> report.sections().get(i).kineticEnergies().get(phase), null));
		}
		sectionModel.setColumns(columns);
	}

	////////  Updating  ////////

	/** Called after every user edit: store the setup with the design and recompute. */
	private void edited() {
		setup.save(document);
		refresh();
	}

	private void refresh() {
		report = RecoveryAnalyzer.analyze(simulation.getSimulatedData().getBranch(branchIndex),
				simulation.getActiveConfiguration(), setup);

		summaryLabel.setText("<html>" + String.join(" &nbsp;&nbsp;|&nbsp;&nbsp; ",
				item("Wyvern.recovery.sum.apogee", distanceUnit, report.apogeeAltitude()),
				item("Wyvern.recovery.sum.descentTime", timeUnit, report.descentTime()),
				item("Wyvern.recovery.sum.landingVelocity", velocityUnit, report.landingVelocity()),
				item("Wyvern.recovery.sum.landingDistance", distanceUnit, report.landingDistance()),
				item("Wyvern.recovery.sum.descentMass", massUnit, report.descentMass())) + "</html>");

		List<String> notes = new ArrayList<>();
		if (!Simulation.isStatusUpToDate(simulation.getStatus())) {
			notes.add(trans.get("Wyvern.recovery.note.outdated"));
		}
		if (!report.reachedGround()) {
			notes.add(trans.get("Wyvern.recovery.note.noLanding"));
		}
		if (report.phases().isEmpty()) {
			notes.add(trans.get("Wyvern.recovery.note.noDeployment"));
		}
		if (report.descentMass() > 0 && Math.abs(report.unassignedMass()) > 0.01 * report.descentMass()) {
			notes.add(String.format(trans.get("Wyvern.recovery.note.unassigned"),
					massUnit.toStringUnit(report.unassignedMass())));
		}
		noteLabel.setText(notes.isEmpty() ? " " : "<html>" + String.join("<br>", notes) + "</html>");

		loadLabel.setText(Double.isNaN(report.maxOpeningForce())
				? trans.get("Wyvern.recovery.lbl.noOpeningForce")
				: String.format(trans.get("Wyvern.recovery.lbl.designLoad"),
						forceUnit.toStringUnit(report.maxOpeningForce()), forceUnit.toStringUnit(report.designLoad())));
		padStayLabel.setText(Double.isNaN(report.padStayTime())
				? trans.get("Wyvern.recovery.lbl.noPadStay")
				: String.format(trans.get("Wyvern.recovery.lbl.padStay"),
						RowTableModel.format(report.padStayTime() / 3600)));

		if (sectionPhaseColumns != report.phases().size()) {
			sectionPhaseColumns = report.phases().size();
			setSectionColumns();
		}
		phaseModel.fireTableDataChanged();
		sectionModel.fireTableDataChanged();
		hardwareModel.fireTableDataChanged();
		chargeModel.fireTableDataChanged();
		batteryModel.fireTableDataChanged();
	}

	private String item(String key, Unit unit, double value) {
		String shown = Double.isNaN(value) ? "-" : unit.toStringUnit(value);
		return trans.get(key) + " <b>" + shown + "</b>";
	}

	////////  Helpers  ////////

	private static <E> Supplier<List<Integer>> indices(Supplier<List<E>> list) {
		return () -> {
			List<Integer> result = new ArrayList<>();
			for (int i = 0; i < list.get().size(); i++) {
				result.add(i);
			}
			return result;
		};
	}

	private <E> JButton removeButton(JTable table, Supplier<List<E>> list) {
		JButton remove = new JButton(trans.get("Wyvern.recovery.but.remove"));
		remove.addActionListener(e -> {
			if (table.isEditing()) {
				table.getCellEditor().cancelCellEditing();
			}
			int row = table.getSelectedRow();
			if (row >= 0 && row < list.get().size()) {
				list.get().remove(row);
				edited();
			}
		});
		return remove;
	}

	private static JSpinner spinner(double value, double min, double max, double step) {
		return new JSpinner(new SpinnerNumberModel(Math.min(Math.max(value, min), max), min, max, step));
	}

	private static Unit unitNamed(UnitGroup group, String name) {
		try {
			return group.getUnit(name);
		} catch (IllegalArgumentException e) {
			return group.getDefaultUnit();
		}
	}

	/** The components a section can be built from: everything directly inside a stage. */
	private List<RocketComponent> sectionCandidates() {
		List<RocketComponent> candidates = new ArrayList<>();
		Rocket rocket = document.getRocket();
		for (AxialStage stage : rocket.getStageList()) {
			candidates.addAll(stage.getChildren());
		}
		return candidates;
	}

	private RecoverySetup.Section wholeRocketSection() {
		RecoverySetup.Section section = new RecoverySetup.Section(trans.get("Wyvern.recovery.section.whole"));
		for (RocketComponent c : sectionCandidates()) {
			section.componentIds.add(c.getID().toString());
		}
		return section;
	}

	private String componentNames(RecoverySetup.Section section) {
		List<String> names = new ArrayList<>();
		Rocket rocket = document.getRocket();
		for (String id : section.componentIds) {
			try {
				RocketComponent c = rocket.findComponent(UUID.fromString(id));
				if (c != null) {
					names.add(c.getName());
				}
			} catch (IllegalArgumentException ignore) {
				// Not a valid ID; skip it
			}
		}
		return String.join(", ", names);
	}

	/**
	 * Let the user tick the components of a section. A component belongs to one section
	 * only, so ticking it here removes it from the others.
	 *
	 * @return true if the user confirmed the dialog
	 */
	private boolean chooseComponents(RecoverySetup.Section section) {
		List<RocketComponent> candidates = sectionCandidates();
		boolean multiStage = document.getRocket().getStageCount() > 1;
		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		List<JCheckBox> boxes = new ArrayList<>();
		for (RocketComponent c : candidates) {
			String label = multiStage ? c.getStage().getName() + ": " + c.getName() : c.getName();
			label += "  (" + massUnit.toStringUnit(RecoveryAnalyzer.componentMass(c)) + ")";
			JCheckBox box = new JCheckBox(label, section.contains(c.getID()));
			boxes.add(box);
			list.add(box);
		}
		JScrollPane scroll = new JScrollPane(list);
		scroll.setPreferredSize(new java.awt.Dimension(380, Math.min(420, 40 + 28 * candidates.size())));
		int choice = JOptionPane.showConfirmDialog(this, scroll,
				trans.get("Wyvern.recovery.but.components") + " - " + section.name,
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) {
			return false;
		}
		section.componentIds.clear();
		for (int i = 0; i < candidates.size(); i++) {
			String id = candidates.get(i).getID().toString();
			if (boxes.get(i).isSelected()) {
				section.componentIds.add(id);
				for (RecoverySetup.Section other : setup.sections) {
					if (other != section) {
						other.componentIds.remove(id);
					}
				}
			}
		}
		return true;
	}

	private double largestInnerDiameter() {
		double largest = 0;
		for (RocketComponent c : document.getRocket()) {
			if (c instanceof BodyTube tube) {
				largest = Math.max(largest, 2 * tube.getInnerRadius());
			}
		}
		return largest;
	}
}
