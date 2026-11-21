package info.openrocket.swing.gui.theme;

import com.formdev.flatlaf.FlatDarculaLaf;

/**
 * The Wyvern look and feel: FlatLaf Darcula with Wyvern's colors on top.
 * <p>
 * FlatLaf loads the defaults of every class in the look and feel hierarchy, so this
 * starts from {@code themes/FlatDarculaLaf.properties} (including all OpenRocket color
 * keys) and then applies the overrides in {@code themes/WyvernLaf.properties}.
 */
public class WyvernLaf extends FlatDarculaLaf {
	public static final String NAME = "Wyvern";

	public static boolean setup() {
		return setup(new WyvernLaf());
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String getDescription() {
		return "Wyvern Look and Feel";
	}
}
