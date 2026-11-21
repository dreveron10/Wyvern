package info.openrocket.core.wyvern.calibration;

import info.openrocket.core.plugin.Plugin;
import info.openrocket.core.simulation.extension.AbstractSimulationExtensionProvider;

@Plugin
public class DragScaleProvider extends AbstractSimulationExtensionProvider {

	public DragScaleProvider() {
		super(DragScale.class, "Wyvern", "Drag scale");
	}
}
