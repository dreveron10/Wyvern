package info.openrocket.core.wyvern.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.OpenRocketDocumentFactory;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.file.GeneralRocketSaver;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.util.TestRockets;

public class RecoverySetupTest extends BaseTestCase {

	private static RecoverySetup sample() {
		RecoverySetup setup = new RecoverySetup();
		RecoverySetup.Section nose = new RecoverySetup.Section("Nose + FOW \"upper\"");
		nose.componentIds.add(UUID.nameUUIDFromBytes("nose".getBytes()).toString());
		nose.extraMass = 0.25;
		setup.sections.add(nose);
		setup.openingForceCoeff = 1.4;
		setup.forceReductionFactor = 0.6;
		setup.safetyFactor = 1.5;
		setup.hardware.add(new RecoverySetup.HardwareItem("3/8 in Kevlar <30 ft>", 16013.6));
		setup.chargeBays.add(new RecoverySetup.ChargeBay("Main bay", 0.1367, 0.2537));
		setup.batteries.add(new RecoverySetup.Battery("1S LiPo", 0.4, 0.05));
		return setup;
	}

	@Test
	@DisplayName("JSON round trip keeps every field")
	public void testJsonRoundTrip() {
		RecoverySetup back = RecoverySetup.fromJson(sample().toJson());

		assertEquals(1, back.sections.size());
		assertEquals("Nose + FOW \"upper\"", back.sections.get(0).name);
		assertEquals(0.25, back.sections.get(0).extraMass, 0);
		assertTrue(back.sections.get(0).contains(UUID.nameUUIDFromBytes("nose".getBytes())));
		assertFalse(back.sections.get(0).contains(UUID.nameUUIDFromBytes("other".getBytes())));
		assertEquals(1.4, back.openingForceCoeff, 0);
		assertEquals(0.6, back.forceReductionFactor, 0);
		assertEquals(1.5, back.safetyFactor, 0);
		assertEquals("3/8 in Kevlar <30 ft>", back.hardware.get(0).name);
		assertEquals(16013.6, back.hardware.get(0).ratedLoad, 0);
		assertEquals(0.2537, back.chargeBays.get(0).length, 0);
		assertEquals(0.25, back.chargeBays.get(0).backupIncrease, 0);
		assertEquals(0.05, back.batteries.get(0).current, 0);
	}

	@Test
	@DisplayName("missing lists become empty and invalid JSON is rejected")
	public void testLenientAndInvalidJson() {
		RecoverySetup setup = RecoverySetup.fromJson("{\"version\":1,\"sections\":[{\"name\":\"A\"}]}");
		assertNotNull(setup.sections.get(0).componentIds);
		assertTrue(setup.hardware.isEmpty());
		assertTrue(setup.chargeBays.isEmpty());
		assertTrue(setup.batteries.isEmpty());
		assertEquals(1.8, setup.openingForceCoeff, 0);

		assertThrows(IllegalArgumentException.class, () -> RecoverySetup.fromJson("not json {"));
		assertThrows(IllegalArgumentException.class, () -> RecoverySetup.fromJson(""));
	}

	@Test
	@DisplayName("setup is stored in and loaded from the document preferences")
	public void testDocumentStorage() {
		OpenRocketDocument document = OpenRocketDocumentFactory.createDocumentFromRocket(TestRockets.makeEstesAlphaIII());

		RecoverySetup fresh = RecoverySetup.load(document);
		assertTrue(fresh.sections.isEmpty());

		sample().save(document);
		RecoverySetup loaded = RecoverySetup.load(document);
		assertEquals("Nose + FOW \"upper\"", loaded.sections.get(0).name);
		assertEquals(1.4, loaded.openingForceCoeff, 0);

		document.getDocumentPreferences().putString(RecoverySetup.PREFERENCE_KEY, "corrupt {");
		assertTrue(RecoverySetup.load(document).sections.isEmpty());
	}

	@Test
	@DisplayName("setup survives saving to an .ork file and loading it again")
	public void testOrkRoundTrip() throws Exception {
		OpenRocketDocument document = OpenRocketDocumentFactory.createNewRocket();
		RecoverySetup setup = sample();
		setup.save(document);

		File file = Files.createTempFile("wyvern-recovery", ".ork").toFile();
		try {
			new GeneralRocketSaver().save(file, document);
			OpenRocketDocument loaded = new GeneralRocketLoader(file).load();

			assertEquals(setup.toJson(), RecoverySetup.load(loaded).toJson());
		} finally {
			Files.deleteIfExists(file.toPath());
		}
	}
}
