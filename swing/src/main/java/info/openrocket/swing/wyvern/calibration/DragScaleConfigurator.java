package info.openrocket.swing.wyvern.calibration;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.plugin.Plugin;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.wyvern.calibration.DragScale;
import info.openrocket.swing.gui.SpinnerEditor;
import info.openrocket.swing.gui.adaptors.DoubleModel;
import info.openrocket.swing.gui.components.BasicSlider;
import info.openrocket.swing.simulation.extension.AbstractSwingSimulationExtensionConfigurator;

@Plugin
public class DragScaleConfigurator extends AbstractSwingSimulationExtensionConfigurator<DragScale> {
	private static final Translator trans = Application.getTranslator();

	public DragScaleConfigurator() {
		super(DragScale.class);
	}

	@Override
	protected JComponent getConfigurationComponent(DragScale extension, Simulation simulation, JPanel panel) {
		panel.add(new JLabel(trans.get("Wyvern.calibration.dragScale.name") + ":"));
		DoubleModel model = new DoubleModel(extension, "Scale", UnitGroup.UNITS_COEFFICIENT, DragScale.MIN_SCALE,
				DragScale.MAX_SCALE);
		JSpinner spinner = new JSpinner(model.getSpinnerModel());
		spinner.setEditor(new SpinnerEditor(spinner));
		panel.add(spinner, "w 65lp!");
		panel.add(new BasicSlider(model.getSliderModel(0.5, 2.0)), "w 100lp, wrap");
		return panel;
	}
}
