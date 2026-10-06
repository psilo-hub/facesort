package free.svoss.facesort.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ConfigModel}: value semantics of the immutable record
 * ({@code with*} copies, {@link ConfigModel#normalized()}) and the clamping
 * that keeps hand-edited config values in ranges the rest of the application
 * can rely on.
 */
class ConfigModelTest {

    @Test
    void withMethods_returnACopyAndLeaveTheOriginalUntouched() {
        ConfigModel original = AppConfig.getDefault();

        ConfigModel changed = original.withMinConfidence(0.5).withDbName("other.db");

        assertNotSame(original, changed, "with* must build a new instance, not mutate this one");
        assertEquals(0.8, original.minConfidence(), "the original must keep its values");
        assertEquals(ConfigModel.DEFAULT_DB_NAME, original.dbName());
        assertEquals(0.5, changed.minConfidence());
        assertEquals("other.db", changed.dbName());
        assertEquals(original.withDbName("other.db").withMinConfidence(0.5), changed,
                "copies of the same values must be equal");
    }

    @Test
    void configModel_hasNoSetters() {
        Method[] setters = Arrays.stream(ConfigModel.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("set"))
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .toArray(Method[]::new);

        assertEquals(0, setters.length,
                "an immutable config must not publish setters, found: " + Arrays.toString(setters));
    }

    @Test
    void normalized_returnsACopyAndLeavesTheOriginalUntouched() {
        ConfigModel handEdited = AppConfig.getDefault().withMinConfidence(1.5);

        ConfigModel config = handEdited.normalized();

        assertEquals(1.0, config.minConfidence());
        assertEquals(1.5, handEdited.minConfidence(), "normalizing must not mutate the input");
        assertNotSame(handEdited, config);
    }

    @Test
    void normalize_clampsOutOfRangeRatiosIntoZeroToOne() {
        ConfigModel config = AppConfig.getDefault()
                .withMinConfidence(1.5)
                .withClusteringThreshold(-0.2)
                .withMinNameSimilarity(1.9)
                .normalized();

        assertEquals(1.0, config.minConfidence());
        assertEquals(0.0, config.clusteringThreshold());
        assertEquals(1.0, config.minNameSimilarity());
    }

    @Test
    void normalize_clampsNonPositiveCountsAndDimensionsToTheirFloors() {
        ConfigModel config = fromInvalidValues().normalized();

        assertEquals(1, config.minBoundingBoxSize());
        assertEquals(1, config.maxFacesPerImage());
        assertEquals(16, config.maxDetectionDimension());
        assertEquals(1, config.thumbnailSize());
        assertEquals(2, config.hnswM());
        assertEquals(1, config.hnswEfConstruction());
        assertEquals(1, config.hnswEfSearch());
        assertEquals(1, config.knnK());
        assertEquals(1, config.faceNameMaxImages());
        assertEquals(1, config.maxFramesPerVideo());
        assertEquals(1, config.faceCropSize());
    }

    @Test
    void normalize_clampsThumbnailQualityIntoAWorkingRange() {
        ConfigModel floor = AppConfig.getDefault().withThumbnailQuality(0.0f).normalized();
        ConfigModel overshoot = AppConfig.getDefault().withThumbnailQuality(2.0f).normalized();

        assertEquals(0.1f, floor.thumbnailQuality(),
                "a quality at or below zero must be clamped to the floor");
        assertEquals(1.0f, overshoot.thumbnailQuality(),
                "a quality above the maximum must be clamped to 1.0");
    }

    @Test
    void normalize_leavesImportBudgetDefaultsUntouched() {
        ConfigModel config = AppConfig.getDefault()
                .withThumbnailQuality(0.7f)
                .withMaxFramesPerVideo(30)
                .withFaceCropSize(128)
                .normalized();

        assertEquals(0.7f, config.thumbnailQuality());
        assertEquals(30, config.maxFramesPerVideo());
        assertEquals(128, config.faceCropSize());
    }

    @Test
    void normalize_clampsNegativeMaxImportThreadsToOne() {
        ConfigModel config = AppConfig.getDefault().withMaxImportThreads(-3).normalized();

        assertEquals(1, config.maxImportThreads());
    }

    @Test
    void normalize_capsMaxImportThreadsAtTheHardBound() {
        ConfigModel config = AppConfig.getDefault().withMaxImportThreads(99).normalized();

        assertEquals(ConfigModel.MAX_IMPORT_THREADS, config.maxImportThreads());
    }

    @Test
    void normalize_leavesSensibleValuesAndDefaultsUntouched() {
        ConfigModel expected = AppConfig.getDefault();

        ConfigModel config = expected.normalized();

        assertEquals(expected.minConfidence(), config.minConfidence());
        assertEquals(expected.clusteringThreshold(), config.clusteringThreshold());
        assertEquals(expected.hnswM(), config.hnswM());
        assertEquals(expected.knnK(), config.knnK());
        assertEquals(expected.maxImportThreads(), config.maxImportThreads());
        assertEquals(expected.minNameSimilarity(), config.minNameSimilarity());
        assertEquals(expected.thumbnailSize(), config.thumbnailSize());
    }

    @Test
    void defaults_matchTheDocumentedDefaults() {
        ConfigModel config = AppConfig.getDefault();

        assertEquals(ConfigModel.DEFAULT_THUMBNAIL_SIZE, config.thumbnailSize());
        assertEquals(ConfigModel.DEFAULT_MAX_DETECTION_DIMENSION, config.maxDetectionDimension());
        assertEquals(ConfigModel.DEFAULT_MAX_IMPORT_THREADS, config.maxImportThreads());
        assertEquals(ConfigModel.DEFAULT_THUMBNAIL_QUALITY, config.thumbnailQuality());
        assertEquals(ConfigModel.DEFAULT_MAX_FRAMES_PER_VIDEO, config.maxFramesPerVideo());
        assertEquals(ConfigModel.DEFAULT_FACE_CROP_SIZE, config.faceCropSize());
        assertEquals(ConfigModel.DEFAULT_DB_NAME, config.dbName());
        assertEquals(ConfigModel.DEFAULT_MIN_NAME_SIMILARITY, config.minNameSimilarity());
        assertEquals(ConfigModel.DEFAULT_FACE_NAME_MAX_IMAGES, config.faceNameMaxImages());
        assertEquals(ConfigModel.DEFAULT_UPDATE_CHECK_ENABLED, config.updateCheckEnabled());
        assertEquals(ConfigModel.DEFAULT_LANGUAGE, config.language());
        assertEquals(ConfigModel.DEFAULT_MIN_BOUNDING_BOX_SIZE, config.minBoundingBoxSize());
        assertEquals(ConfigModel.DEFAULT_MIN_CONFIDENCE, config.minConfidence());
        assertEquals(ConfigModel.DEFAULT_MAX_FACES_PER_IMAGE, config.maxFacesPerImage());
        assertEquals(ConfigModel.DEFAULT_CLUSTERING_THRESHOLD, config.clusteringThreshold());
        assertEquals(ConfigModel.DEFAULT_HNSW_M, config.hnswM());
        assertEquals(ConfigModel.DEFAULT_HNSW_EF_CONSTRUCTION, config.hnswEfConstruction());
        assertEquals(ConfigModel.DEFAULT_HNSW_EF_SEARCH, config.hnswEfSearch());
        assertEquals(ConfigModel.DEFAULT_KNN_K, config.knnK());
        assertEquals("", config.lastImportFolder());
        assertFalse(config.language().isBlank(), "the default language must be usable as-is");
        assertTrue(config.faceaiCacheDir() == null,
                "the FaceAI cache dir is unset until the user picks one");
    }

    private static ConfigModel fromInvalidValues() {
        return AppConfig.getDefault()
                .withMinBoundingBoxSize(0)
                .withMaxFacesPerImage(-4)
                .withMaxDetectionDimension(-1)
                .withThumbnailSize(-1)
                .withHnswM(0)
                .withHnswEfConstruction(-6)
                .withHnswEfSearch(0)
                .withKnnK(-5)
                .withFaceNameMaxImages(-2)
                .withMaxFramesPerVideo(-3)
                .withFaceCropSize(0);
    }
}
