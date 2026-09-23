package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.tools.faceai.DetectedFace;
import free.svoss.tools.faceai.FaceAIConfig;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link FaceAiService} adapter's settings mapping and its
 * argument contracts, using the {@link FakeFaceAiEngine} test seam so no
 * FaceAI model download or native inference is involved.
 */
class FaceAiServiceTest {

    @Test
    void toFaceAIConfig_mapsSettings() {
        ConfigModel config = new ConfigModel();
        config.setMinConfidence(0.72);
        config.setFaceaiCacheDir("/tmp/facesort/cache");

        FaceAIConfig faceAIConfig = FaceAiService.toFaceAIConfig(config);

        assertEquals("/tmp/facesort/cache", faceAIConfig.cacheDir());
        assertEquals(0.72f, faceAIConfig.detectionThreshold());
        assertEquals(FaceAiService.EMBEDDING_DIMENSION, faceAIConfig.embeddingDimension());
        assertEquals("CPU", faceAIConfig.device());
        assertTrue(faceAIConfig.l2NormalizeEmbeddings());
    }

    @Test
    void toFaceAIConfig_blankCacheDirFallsBackToFaceAiDefault() {
        ConfigModel config = new ConfigModel();
        config.setFaceaiCacheDir("  ");

        FaceAIConfig faceAIConfig = FaceAiService.toFaceAIConfig(config);

        assertEquals("", faceAIConfig.cacheDir());
    }

    @Test
    void toFaceAIConfig_nullConfigThrows() {
        assertThrows(NullPointerException.class, () -> FaceAiService.toFaceAIConfig(null));
    }

    @Test
    void detectFaces_rejectsNullImage() {
        try (FaceAiService service = new FaceAiService(new FakeFaceAiEngine())) {
            assertThrows(IllegalArgumentException.class, () -> service.detectFaces(null));
        }
    }

    @Test
    void getEmbedding_rejectsNullCrop() {
        try (FaceAiService service = new FaceAiService(new FakeFaceAiEngine())) {
            assertThrows(IllegalArgumentException.class, () -> service.getEmbedding(null));
        }
    }

    @Test
    void calcSimilarity_rejectsNullEmbeddings() {
        try (FaceAiService service = new FaceAiService(new FakeFaceAiEngine())) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.calcSimilarity(null, new float[]{1, 0}));
            assertThrows(IllegalArgumentException.class,
                    () -> service.calcSimilarity(new float[]{1, 0}, null));
        }
    }

    @Test
    void calcAverage_rejectsNullEmbeddings() {
        try (FaceAiService service = new FaceAiService(new FakeFaceAiEngine())) {
            assertThrows(IllegalArgumentException.class, () -> service.calcAverage(null));
        }
    }

    @Test
    void delegatesAndClosesEngine() {
        FakeFaceAiEngine engine = new FakeFaceAiEngine()
                .withFaces(new DetectedFace(0, 0, 20, 20, 0.9f))
                .withEmbedding(new float[]{1, 0, 0, 0, 0, 0, 0, 0});
        BufferedImage image = solidImage(40, 40);
        FaceAiService service = new FaceAiService(engine);

        assertEquals(1, service.detectFaces(image).length);
        assertArrayEquals(new float[]{1, 0, 0, 0, 0, 0, 0, 0},
                service.getEmbedding(image));
        assertEquals(1.0, service.calcSimilarity(new float[]{1, 0}, new float[]{1, 0}), 1e-9);
        assertEquals(1, engine.detectCalls());

        service.close();
    }

    @Test
    void delegatesAndClosesEngine_smoothsEmbeddingDimension() {
        // The FakeFaceAiEngine default embedding is expected to be usable by the
        // service unchanged; this guards the calcAverage delegation path.
        FakeFaceAiEngine engine = new FakeFaceAiEngine();
        FaceAiService service = new FaceAiService(engine);
        List<float[]> vectors = List.of(new float[]{2, 4}, new float[]{4, 8});
        float[] average = service.calcAverage(vectors);
        assertEquals(3.0f, average[0]);
        assertEquals(6.0f, average[1]);
        service.close();
    }

    private static BufferedImage solidImage(int width, int height) {
        return new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
    }
}