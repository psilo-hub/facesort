package free.svoss.facesort.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AppConfig}: defaults and backward compatibility of the
 * config file with the settings introduced for the "Add faces to a name" tab and the
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
    void load_invalidHandEditedValues_areClampedByNormalize() throws Exception {
        Path file = tempDir.resolve("tampered.json");
        Files.writeString(file, "{ \"minConfidence\": 1.7, \"hnswM\": 0, \"maxImportThreads\": -2 }\n");

        ConfigModel config = AppConfig.load(file);

        assertEquals(1.0, config.getMinConfidence(),
                "a detection threshold above 100% must be clamped");
        assertEquals(2, config.getHnswM(), "a zero HNSW M would surface as an index error");
        assertEquals(1, config.getMaxImportThreads(),
                "a negative thread count must be clamped");
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
        assertEquals(ConfigModel.DEFAULT_THUMBNAIL_QUALITY, config.getThumbnailQuality(),
                "a missing thumbnailQuality key must fall back to the default");
        assertEquals(ConfigModel.DEFAULT_MAX_FRAMES_PER_VIDEO, config.getMaxFramesPerVideo(),
                "a missing maxFramesPerVideo key must fall back to the default");
        assertEquals(ConfigModel.DEFAULT_FACE_CROP_SIZE, config.getFaceCropSize(),
                "a missing faceCropSize key must fall back to the default");
        assertEquals("custom.db", config.getDbName(), "the pre-existing keys must still load");
    }

    @Test
    void load_fileWithAnUnknownKey_ignoresItInsteadOfRefusingToStart() throws Exception {
        Path file = tempDir.resolve("unknown-key.json");
        Files.writeString(file, "{\n  \"dbName\": \"kept.db\",\n  \"someFutureSetting\": 42\n}\n");

        ConfigModel config = AppConfig.load(file);

        assertEquals("kept.db", config.getDbName(), "the known keys must still be read");
        assertEquals(ConfigModel.DEFAULT_MAX_IMPORT_THREADS, config.getMaxImportThreads(),
                "an unknown key must not disturb the defaults of the keys it shadows");
    }

    @Test
    void saveAndLoad_roundTripsNewSettings() throws Exception {
        Path file = tempDir.resolve("config.json");
        ConfigModel written = AppConfig.getDefault();
        written.setUpdateCheckEnabled(false);
        written.setFaceNameMaxImages(42);
        written.setThumbnailQuality(0.6f);
        written.setMaxFramesPerVideo(30);
        written.setFaceCropSize(128);
        AppConfig.save(file, written);

        ConfigModel read = AppConfig.load(file);

        assertFalse(read.isUpdateCheckEnabled());
        assertEquals(42, read.getFaceNameMaxImages());
        assertEquals(0.6f, read.getThumbnailQuality());
        assertEquals(30, read.getMaxFramesPerVideo());
        assertEquals(128, read.getFaceCropSize());
        assertTrue(Files.exists(file));
    }

    @Test
    void save_failedWrite_leavesThePreviousConfigIntact() throws Exception {
        Path file = tempDir.resolve("config.json");
        ConfigModel saved = AppConfig.getDefault();
        saved.setDbName("survivor.db");
        AppConfig.save(file, saved);
        String before = Files.readString(file);

        ConfigModel exploding = new ConfigModel() {
            @Override
            public String getDbName() {
                throw new SimulatedWriteFailure();
            }
        };
        Throwable thrown = assertThrows(Throwable.class, () -> AppConfig.save(file, exploding));

        assertTrue(causedBy(thrown, SimulatedWriteFailure.class),
                "the simulated serialization failure must still be reported, got: " + thrown);
        assertEquals(before, Files.readString(file),
                "a failed save must not damage the settings the user already had");
        assertEquals("survivor.db", AppConfig.load(file).getDbName(),
                "the config on disk must still be loadable after a failed save");
    }

    @Test
    void save_overwritesAnExistingConfigAndLeavesNoTemporaryFiles() throws Exception {
        Path file = tempDir.resolve("config.json");
        AppConfig.save(file, AppConfig.getDefault());

        ConfigModel second = AppConfig.getDefault();
        second.setDbName("second.db");
        second.setKnnK(7);
        AppConfig.save(file, second);

        assertEquals(List.of("config.json"), fileNamesIn(tempDir),
                "saving must replace the config in place, without leaving temporary files behind");
        ConfigModel reloaded = AppConfig.load(file);
        assertEquals("second.db", reloaded.getDbName());
        assertEquals(7, reloaded.getKnnK());
    }

    private static List<String> fileNamesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static boolean causedBy(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    /** Marker for a config that fails while being written, see the test above. */
    private static final class SimulatedWriteFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}