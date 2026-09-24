package free.svoss.facesort.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ConfigModel#normalize()}, which clamps hand-edited config
 * values into ranges the rest of the application can rely on.
 */
class ConfigModelTest {

    @Test
    void normalize_clampsOutOfRangeRatiosIntoZeroToOne() {
        ConfigModel config = new ConfigModel();
        config.setMinConfidence(1.5);
        config.setClusteringThreshold(-0.2);
        config.setMinNameSimilarity(1.9);

        config.normalize();

        assertEquals(1.0, config.getMinConfidence());
        assertEquals(0.0, config.getClusteringThreshold());
        assertEquals(1.0, config.getMinNameSimilarity());
    }

    @Test
    void normalize_clampsNonPositiveCountsAndDimensionsToTheirFloors() {
        ConfigModel config = fromInvalidValues();

        config.normalize();

        assertEquals(1, config.getMinBoundingBoxSize());
        assertEquals(1, config.getMaxFacesPerImage());
        assertEquals(16, config.getMaxDetectionDimension());
        assertEquals(1, config.getThumbnailSize());
        assertEquals(2, config.getHnswM());
        assertEquals(1, config.getHnswEfConstruction());
        assertEquals(1, config.getHnswEfSearch());
        assertEquals(1, config.getKnnK());
        assertEquals(1, config.getFaceNameMaxImages());
        assertEquals(1, config.getMaxFramesPerVideo());
        assertEquals(1, config.getFaceCropSize());
    }

    @Test
    void normalize_clampsThumbnailQualityIntoAWorkingRange() {
        ConfigModel config = new ConfigModel();
        config.setThumbnailQuality(0.0f);
        ConfigModel overshoot = new ConfigModel();
        overshoot.setThumbnailQuality(2.0f);

        config.normalize();
        overshoot.normalize();

        assertEquals(0.1f, config.getThumbnailQuality(),
                "a quality at or below zero must be clamped to the floor");
        assertEquals(1.0f, overshoot.getThumbnailQuality(),
                "a quality above the maximum must be clamped to 1.0");
    }

    @Test
    void normalize_leavesImportBudgetDefaultsUntouched() {
        ConfigModel config = new ConfigModel();
        config.setThumbnailQuality(0.7f);
        config.setMaxFramesPerVideo(30);
        config.setFaceCropSize(128);

        config.normalize();

        assertEquals(0.7f, config.getThumbnailQuality());
        assertEquals(30, config.getMaxFramesPerVideo());
        assertEquals(128, config.getFaceCropSize());
    }

    @Test
    void normalize_clampsNegativeMaxImportThreadsToOne() {
        ConfigModel config = new ConfigModel();
        config.setMaxImportThreads(-3);

        config.normalize();

        assertEquals(1, config.getMaxImportThreads());
    }

    @Test
    void normalize_capsMaxImportThreadsAtTheHardBound() {
        ConfigModel config = new ConfigModel();
        config.setMaxImportThreads(99);

        config.normalize();

        assertEquals(ConfigModel.MAX_IMPORT_THREADS, config.getMaxImportThreads());
    }

    @Test
    void normalize_leavesSensibleValuesAndDefaultsUntouched() {
        ConfigModel config = AppConfig.getDefault();

        config.normalize();

        ConfigModel expected = AppConfig.getDefault();
        assertEquals(expected.getMinConfidence(), config.getMinConfidence());
        assertEquals(expected.getClusteringThreshold(), config.getClusteringThreshold());
        assertEquals(expected.getHnswM(), config.getHnswM());
        assertEquals(expected.getKnnK(), config.getKnnK());
        assertEquals(expected.getMaxImportThreads(), config.getMaxImportThreads());
        assertEquals(expected.getMinNameSimilarity(), config.getMinNameSimilarity());
        assertEquals(expected.getThumbnailSize(), config.getThumbnailSize());
    }

    private static ConfigModel fromInvalidValues() {
        ConfigModel config = new ConfigModel();
        config.setMinBoundingBoxSize(0);
        config.setMaxFacesPerImage(-4);
        config.setMaxDetectionDimension(-1);
        config.setThumbnailSize(-1);
        config.setHnswM(0);
        config.setHnswEfConstruction(-6);
        config.setHnswEfSearch(0);
        config.setKnnK(-5);
        config.setFaceNameMaxImages(-2);
        config.setMaxFramesPerVideo(-3);
        config.setFaceCropSize(0);
        return config;
    }
}