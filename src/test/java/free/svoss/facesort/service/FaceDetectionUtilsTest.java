package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.tools.faceai.DetectedFace;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link FaceDetectionUtils}: criteria filtering, the max-faces cap,
 * faces dropped on embedding failure, detection on scaled-down images
 * (mapping back to original coordinates) and the criteria JSON.
 */
class FaceDetectionUtilsTest {

    private static final String SOURCE = "unit-test";

    @Test
    void detectFaces_filtersBelowSizeAndConfidence() {
        FakeFaceAiEngine engine = new FakeFaceAiEngine().withFaces(
                new DetectedFace(10, 10, 100, 100, 0.95f),  // qualifies
                new DetectedFace(10, 10, 60, 60, 0.95f),    // too small
                new DetectedFace(10, 10, 100, 100, 0.5f));  // low confidence
        try (FaceAiService service = new FaceAiService(engine)) {
            BufferedImage image = solidImage(200, 200);

            FaceDetectionUtils.DetectionResult result =
                    FaceDetectionUtils.detectFaces("img1", image, 1600,
                            80, 0.8, 10, ConfigModel.DEFAULT_FACE_CROP_SIZE,
                             ConfigModel.DEFAULT_THUMBNAIL_QUALITY, service, SOURCE);

            assertEquals(1, result.faces().size());
            assertEquals(0, result.droppedFaces());
            FaceRecord only = result.faces().get(0);
            assertEquals("img1", only.imageHash());
            assertEquals(10, only.bboxX());
            assertEquals(10, only.bboxY());
            assertEquals(100, only.bboxW());
            assertEquals(100, only.bboxH());
            assertEquals(0.95, only.confidence(), 1e-6);
            assertEquals(8, only.embedding().length,
                    "the fake embedding is used as-is for stored faces");
            assertTrue(only.subImageJpg() != null && only.subImageJpg().length > 0,
                    "a downscaled sub-image JPEG must be stored");
        }
    }

    @Test
    void detectFaces_respectsMaxFacesPerImage() {
        FakeFaceAiEngine engine = new FakeFaceAiEngine().withFaces(
                new DetectedFace(10, 10, 100, 100, 0.95f),
                new DetectedFace(10, 10, 100, 100, 0.95f),
                new DetectedFace(10, 10, 100, 100, 0.95f));
        try (FaceAiService service = new FaceAiService(engine)) {

            FaceDetectionUtils.DetectionResult result =
                    FaceDetectionUtils.detectFaces("img1", solidImage(200, 200), 1600,
                            80, 0.8, 2, ConfigModel.DEFAULT_FACE_CROP_SIZE,
                             ConfigModel.DEFAULT_THUMBNAIL_QUALITY, service, SOURCE);

            assertEquals(2, result.faces().size());
            assertEquals(0, result.droppedFaces());
        }
    }

    @Test
    void detectFaces_countsEmbeddingFailuresAsDroppedWithoutLosingOtherFaces() {
        FailingSecondEmbeddingEngine engine =
                new FailingSecondEmbeddingEngine(
                        new DetectedFace(10, 10, 100, 100, 0.95f),
                        new DetectedFace(10, 10, 90, 90, 0.95f));
        try (FaceAiService service = new FaceAiService(engine)) {

            FaceDetectionUtils.DetectionResult result =
                    FaceDetectionUtils.detectFaces("img1", solidImage(200, 200), 1600,
                            80, 0.8, 10, ConfigModel.DEFAULT_FACE_CROP_SIZE,
                             ConfigModel.DEFAULT_THUMBNAIL_QUALITY, service, SOURCE);

            assertEquals(1, result.faces().size(),
                    "the face whose embedding failed must be dropped");
            assertEquals(1, result.droppedFaces());
            assertEquals(10, result.faces().get(0).bboxX());
        }
    }

    @Test
    void detectFaces_backsOffWhenEveryEmbeddingFails() {
        FakeFaceAiEngine engine = new FakeFaceAiEngine()
                .withFaces(new DetectedFace(10, 10, 100, 100, 0.95f))
                .withFailingEmbedding();
        try (FaceAiService service = new FaceAiService(engine)) {

            FaceDetectionUtils.DetectionResult result =
                    FaceDetectionUtils.detectFaces("img1", solidImage(200, 200), 1600,
                            80, 0.8, 10, ConfigModel.DEFAULT_FACE_CROP_SIZE,
                             ConfigModel.DEFAULT_THUMBNAIL_QUALITY, service, SOURCE);

            assertEquals(0, result.faces().size());
            assertEquals(1, result.droppedFaces());
        }
    }

    @Test
    void detectFaces_mapsBoundingBoxesBackToOriginalCoordinates() {
        // 1000x800 downscaled to <=500 -> scale 0.5; faces are detected on the
        // half-size image and must be inflated back for storage.
        FakeFaceAiEngine engine = new FakeFaceAiEngine()
                .withFaces(new DetectedFace(100, 100, 200, 200, 0.95f));
        try (FaceAiService service = new FaceAiService(engine)) {

            FaceDetectionUtils.DetectionResult result =
                    FaceDetectionUtils.detectFaces("img1", solidImage(1000, 800), 500,
                            80, 0.8, 10, ConfigModel.DEFAULT_FACE_CROP_SIZE,
                             ConfigModel.DEFAULT_THUMBNAIL_QUALITY, service, SOURCE);

            assertEquals(1, result.faces().size());
            FaceRecord scaled = result.faces().get(0);
            assertEquals(200, scaled.bboxX());
            assertEquals(200, scaled.bboxY());
            assertEquals(400, scaled.bboxW());
            assertEquals(400, scaled.bboxH());
        }
    }

    @Test
    void detectFaces_lowConfidenceFacesSurviveScalingMapping() {
        FakeFaceAiEngine engine = new FakeFaceAiEngine()
                .withFaces(new DetectedFace(10, 20, 200, 200, 0.9f));
        try (FaceAiService service = new FaceAiService(engine)) {

            FaceDetectionUtils.DetectionResult result =
                    FaceDetectionUtils.detectFaces("img1", solidImage(1000, 800), 500,
                            100, 0.8, 10, ConfigModel.DEFAULT_FACE_CROP_SIZE,
                             ConfigModel.DEFAULT_THUMBNAIL_QUALITY, service, SOURCE);

            assertEquals(1, result.faces().size(),
                    "minBbox is evaluated in original coordinates after mapping");
            assertEquals(400, result.faces().get(0).bboxW());
        }
    }

    @Test
    void detectFaces_downscalesStoredFaceCropsToConfiguredSize() throws Exception {
        FakeFaceAiEngine engine = new FakeFaceAiEngine()
                .withFaces(new DetectedFace(0, 0, 200, 200, 0.95f));
        try (FaceAiService service = new FaceAiService(engine)) {

            FaceDetectionUtils.DetectionResult result =
                    FaceDetectionUtils.detectFaces("img1", solidImage(200, 200), 1600,
                            80, 0.8, 10, 50, 0.85f, service, SOURCE);

            assertEquals(1, result.faces().size());
            BufferedImage stored = ImageIO.read(new ByteArrayInputStream(
                    result.faces().get(0).subImageJpg()));
            assertEquals(50, stored.getWidth(),
                    "the stored face crop must be downscaled to the configured size");
            assertEquals(50, stored.getHeight(),
                    "the stored face crop must be downscaled to the configured size");
        }
    }

    @Test
    void detectAllFaces_mapsLandmarksBackToOriginalCoordinates() {
        // Same 0.5 detection scale as above: landmarks must be inflated with the
        // bounding box, otherwise a rotation read off them would come from a
        // shrunken face.
        FakeFaceAiEngine engine = new FakeFaceAiEngine().withFaces(
                new DetectedFace(100, 100, 200, 200, 0.95f,
                        new float[]{120, 130, 160, 130, 140, 160, 125, 190, 155, 190}));
        try (FaceAiService service = new FaceAiService(engine)) {

            List<DetectedFace> faces =
                    FaceDetectionUtils.detectAllFaces(solidImage(1000, 800), 500, service);

            assertEquals(1, faces.size());
            DetectedFace mapped = faces.get(0);
            assertEquals(200, mapped.x());
            assertEquals(200, mapped.y());
            assertEquals(400, mapped.width());
            assertEquals(400, mapped.height());
            assertTrue(mapped.hasLandmarks(), "landmarks must survive the mapping");
            assertArrayEquals(new float[]{240, 260, 320, 260, 280, 320, 250, 380, 310, 380},
                    mapped.landmarks(), 1e-3f,
                    "landmarks must be scaled with the same factor as the bounding box");
        }
    }

    @Test
    void detectFaces_storesASidewaysFaceUpright() throws Exception {
        // A face recorded rotated a quarter turn clockwise: the eyes sit to the
        // right of the mouth, so the stored crop must come out upright, which
        // swaps its sides.
        FakeFaceAiEngine engine = new FakeFaceAiEngine().withFaces(
                new DetectedFace(10, 10, 20, 40, 0.95f, new float[]{
                        30, 30,   // left eye
                        30, 40,   // right eye
                        25, 35,   // nose tip
                        20, 33,   // left mouth corner
                        20, 37})); // right mouth corner
        try (FaceAiService service = new FaceAiService(engine)) {

            FaceDetectionUtils.DetectionResult result =
                    FaceDetectionUtils.detectFaces("img1", solidImage(100, 100), 1600,
                            10, 0.8, 10, 200, 0.95f, service, SOURCE);

            assertEquals(1, result.faces().size());
            assertEquals(0, result.droppedFaces());
            FaceRecord stored = result.faces().get(0);
            assertEquals(20, stored.bboxW());
            assertEquals(40, stored.bboxH());
            BufferedImage crop = ImageIO.read(new ByteArrayInputStream(stored.subImageJpg()));
            assertEquals(40, crop.getWidth(),
                    "the stored crop must be the sideways face turned upright");
            assertEquals(20, crop.getHeight(),
                    "the stored crop must be the sideways face turned upright");
        }
    }

    @Test
    void buildCriteriaJson_formatsCompactJson() {
        assertEquals("{\"minBbox\":80,\"minConfidence\":0.80,\"maxFacesPerImage\":10}",
                FaceDetectionUtils.buildCriteriaJson(80, 0.8, 10));
        assertEquals("{\"minBbox\":0,\"minConfidence\":0.70,\"maxFacesPerImage\":0}",
                FaceDetectionUtils.buildCriteriaJson(0, 0.7, 0));
    }

    private static BufferedImage solidImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.GRAY);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Engine whose second embedding inference always fails. */
    private static final class FailingSecondEmbeddingEngine extends FakeFaceAiEngine {

        private final AtomicInteger embeddingCalls = new AtomicInteger();

        FailingSecondEmbeddingEngine(DetectedFace... faces) {
            withFaces(faces);
        }

        @Override
        public float[] getEmbedding(BufferedImage faceCrop) {
            if (embeddingCalls.incrementAndGet() == 2) {
                throw new IllegalStateException("embedding inference failed");
            }
            return super.getEmbedding(faceCrop);
        }
    }
}