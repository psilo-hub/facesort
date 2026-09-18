package free.svoss.facesort.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AppConfig}: defaults and backward compatibility of the
 * config file with the settings introduced for the "Face Name" tab and the
 * update check.
 */
class AppConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void load_absentFile_usesDefaults() throws Exception {
        ConfigModel config = AppConfig.load(tempDir.resolve("missing.json"));

        assertEquals(ConfigModel.DEFAULT_UPDATE_CHECK_ENABLED, config.isUpdateCheckEnabled());
        assertEquals(ConfigModel.DEFAULT_FACE_NAME_MAX_IMAGES, config.getFaceNameMaxImages());
    }

    @Test
    void load_fileWithoutNewKeys_keepsDefaults() throws Exception {
        Path file = tempDir.resolve("old-config.json");
        Files.writeString(file, "{ \"dbName\": \"custom.db\", \"minNameSimilarity\": 0.5 }\n");

        ConfigModel config = AppConfig.load(file);

        assertEquals(ConfigModel.DEFAULT_UPDATE_CHECK_ENABLED, config.isUpdateCheckEnabled(),
                "a missing updateCheckEnabled key must fall back to enabled");
        assertEquals(ConfigModel.DEFAULT_FACE_NAME_MAX_IMAGES, config.getFaceNameMaxImages(),
                "a missing faceNameMaxImages key must fall back to the default");
        assertEquals("custom.db", config.getDbName(), "the pre-existing keys must still load");
    }

    @Test
    void saveAndLoad_roundTripsNewSettings() throws Exception {
        Path file = tempDir.resolve("config.json");
        ConfigModel written = AppConfig.getDefault();
        written.setUpdateCheckEnabled(false);
        written.setFaceNameMaxImages(42);
        AppConfig.save(file, written);

        ConfigModel read = AppConfig.load(file);

        assertFalse(read.isUpdateCheckEnabled());
        assertEquals(42, read.getFaceNameMaxImages());
        assertTrue(Files.exists(file));
    }
}