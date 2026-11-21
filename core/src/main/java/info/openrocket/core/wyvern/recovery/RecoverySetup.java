package info.openrocket.core.wyvern.recovery;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.preferences.DocumentPreferences;

/**
 * Everything the user enters for the recovery analysis of one design: how the rocket
 * splits into independent sections, the opening force assumptions, the recovery
 * hardware ratings, the ejection charge bays and the avionics batteries.
 * <p>
 * The setup is stored as one JSON string in the document preferences, which are saved
 * inside the .ork file. Plain OpenRocket keeps or ignores the entry, so files stay
 * interchangeable. All quantities are in SI units.
 */
public class RecoverySetup {

	/** Document preference key that holds the JSON form of the setup. */
	public static final String PREFERENCE_KEY = "Wyvern.recovery";

	private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();

	private static final double PSI = 6894.757293168;

	/** A part of the rocket that lands as one tethered or separate body. */
	public static class Section {
		public String name = "";
		/** IDs of the components (with everything inside them) that make up the section. */
		public List<String> componentIds = new ArrayList<>();
		/** Mass not modeled as components, in kg. */
		public double extraMass = 0;

		public Section() {
		}

		public Section(String name) {
			this.name = name;
		}

		public boolean contains(UUID id) {
			return componentIds.contains(id.toString());
		}
	}

	/** A load-bearing recovery part, such as a shock cord, quick link or eyebolt. */
	public static class HardwareItem {
		public String name = "";
		/** Rated load in N (working load limit or breaking strength, whichever the user compares against). */
		public double ratedLoad = 0;

		public HardwareItem() {
		}

		public HardwareItem(String name, double ratedLoad) {
			this.name = name;
			this.ratedLoad = ratedLoad;
		}
	}

	/** A bay pressurized by an ejection charge. */
	public static class ChargeBay {
		public String name = "";
		/** Inner diameter in m. */
		public double diameter = 0;
		/** Free length in m. */
		public double length = 0;
		/** Target pressure rise in Pa. */
		public double pressure = 15 * PSI;
		/** Fractional increase of the backup charge over the primary. */
		public double backupIncrease = 0.25;
		/** Number of shear pins holding the joint; 0 to skip the shear pin check. */
		public int shearPinCount = 0;
		/** Force that shears one pin, in N. */
		public double shearPinStrength = 0;

		public ChargeBay() {
		}

		public ChargeBay(String name, double diameter, double length) {
			this.name = name;
			this.diameter = diameter;
			this.length = length;
		}
	}

	/** A battery and the device it powers, for pad stay time. */
	public static class Battery {
		public String name = "";
		/** Capacity in ampere hours. */
		public double capacity = 0;
		/** Average current draw while armed on the pad, in A. */
		public double current = 0;
		/** Fraction of the rated capacity that is actually usable. */
		public double usableFraction = 0.8;

		public Battery() {
		}

		public Battery(String name, double capacity, double current) {
			this.name = name;
			this.capacity = capacity;
			this.current = current;
		}
	}

	public int version = 1;
	public List<Section> sections = new ArrayList<>();
	/** Opening force coefficient Cx applied to every canopy. */
	public double openingForceCoeff = 1.8;
	/** Opening force reduction factor X1; 1 is the conservative infinite mass case. */
	public double forceReductionFactor = 1.0;
	/** Safety factor applied to the largest opening force to get the hardware design load. */
	public double safetyFactor = 2.0;
	public List<HardwareItem> hardware = new ArrayList<>();
	public List<ChargeBay> chargeBays = new ArrayList<>();
	public List<Battery> batteries = new ArrayList<>();

	public String toJson() {
		return GSON.toJson(this);
	}

	/**
	 * Parse a setup from JSON. Missing lists become empty lists so callers never see null.
	 *
	 * @throws IllegalArgumentException if the text is not a valid setup
	 */
	public static RecoverySetup fromJson(String json) {
		RecoverySetup setup;
		try {
			setup = GSON.fromJson(json, RecoverySetup.class);
		} catch (JsonSyntaxException e) {
			throw new IllegalArgumentException("Invalid recovery setup: " + e.getMessage(), e);
		}
		if (setup == null) {
			throw new IllegalArgumentException("Invalid recovery setup: empty");
		}
		if (setup.sections == null) {
			setup.sections = new ArrayList<>();
		}
		for (Section s : setup.sections) {
			if (s.componentIds == null) {
				s.componentIds = new ArrayList<>();
			}
		}
		if (setup.hardware == null) {
			setup.hardware = new ArrayList<>();
		}
		if (setup.chargeBays == null) {
			setup.chargeBays = new ArrayList<>();
		}
		if (setup.batteries == null) {
			setup.batteries = new ArrayList<>();
		}
		return setup;
	}

	/**
	 * Load the setup stored in a document, or a new default setup if the document has
	 * none or its entry cannot be read.
	 */
	public static RecoverySetup load(OpenRocketDocument document) {
		String json = document.getDocumentPreferences().getString(PREFERENCE_KEY, null);
		if (json == null || json.isBlank()) {
			return new RecoverySetup();
		}
		try {
			return fromJson(json);
		} catch (IllegalArgumentException e) {
			return new RecoverySetup();
		}
	}

	/**
	 * Store this setup in the document. The document is marked as changed only if the
	 * stored value actually differs.
	 */
	public void save(OpenRocketDocument document) {
		DocumentPreferences prefs = document.getDocumentPreferences();
		prefs.putString(PREFERENCE_KEY, toJson());
	}
}
