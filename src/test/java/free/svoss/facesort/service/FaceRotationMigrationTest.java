package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.config.ConfigStore;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.MetaDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.tools.faceai.DetectedFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link FaceRotationMigration}: it re-derives the stored crop of
 * every sideways face from the original, leaves everything else alone, and
 * records its own completion so later starts do no work.
 *
 * <p>The originals come from an in-memory {@link FaceImageSource}, so the pass
 * runs without touching the disk.</p>
 */
class FaceRotationMigrationTest {

    private static final byte[] OLD_JPEG = {1, 2, 3};
    private static final float[] OLD_EMBEDDING = {0.25f, 0.75f};

    private Database db;
    private ImageDao imageDao;
    private FaceDao faceDao;
    private MetaDao metaDao;
    private ConfigStore configStore;
    private FakeImageSource imageSource;
    private final Set<String> storedImages = new HashSet<>();

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        imageDao = new ImageDao(db.getConnection());
        faceDao = new FaceDao(db.getConnection());
        metaDao = new MetaDao(db.getConnection());
        configStore = new ConfigStore(ConfigModel.defaults());
        imageSource = new FakeImageSource();
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    @Test
    void run_straightensASidewaysStoredFace() throws Exception {
        long id = storeFace("img", 10, 10, 20, 40);
        imageSource.put("img", solidImage(100, 100));
        FakeFaceAiEngine engine = new FakeFaceAiEngine()
                .withFaces(new DetectedFace(10, 10, 20, 40, 0.95f, sidewaysLandmarks()))
                .withEmbedding(new float[]{0.5f, 0.5f});

        FaceRotationMigration.Result result = run(engine);

        assertEquals(1, result.total());
        assertEquals(1, result.rotated());
        assertEquals(0, result.skipped());
        assertEquals(0, result.failed());
        assertTrue(result.completed());

        FaceRecord updated = faceDao.findById(id).orElseThrow();
        assertArrayEquals(new float[]{0.5f, 0.5f}, updated.embedding(),
                "the crop gets the embedding computed for its upright self");
        BufferedImage thumbnail = ImageIO.read(new ByteArrayInputStream(updated.subImageJpg()));
        assertEquals(40, thumbnail.getWidth(), "the sideways crop is stored upright");
        assertEquals(20, thumbnail.getHeight(), "the sideways crop is stored upright");
        assertEquals(10, updated.bboxX(), "the stored box identifies the face and must not move");
        assertEquals(10, updated.bboxY(), "the stored box identifies the face and must not move");
        assertEquals(20, updated.bboxW(), "the stored box identifies the face and must not change");
        assertEquals(40, updated.bboxH(), "the stored box identifies the face and must not change");
    }

    @Test
    void run_leavesAnUprightFaceExactlyAsItWas() throws Exception {
        long id = storeFace("img", 10, 10, 20, 40);
        imageSource.put("img", solidImage(100, 100));
        FakeFaceAiEngine engine = new FakeFaceAiEngine()
                .withFaces(new DetectedFace(10, 10, 20, 40, 0.95f, uprightLandmarks()));

        FaceRotationMigration.Result result = run(engine);

        assertEquals(1, result.skipped());
        assertEquals(0, result.rotated());
        FaceRecord untouched = faceDao.findById(id).orElseThrow();
        assertArrayEquals(OLD_EMBEDDING, untouched.embedding(),
                "an upright crop must not be re-embedded");
        assertArrayEquals(OLD_JPEG, untouched.subImageJpg(),
                "an upright crop must not be re-encoded");
    }

    @Test
    void run_keepsTheAssignedName() throws Exception {
        imageDao.insert("img", 1L, "{}", 1);
        long nameId = new NameDao(db.getConnection()).insert("Alice");
        long id = faceDao.insert(new FaceRecord(0, "img", 10, 10, 20, 40, 0.9f,
                OLD_EMBEDDING, OLD_JPEG, nameId));
        imageSource.put("img", solidImage(100, 100));
        FakeFaceAiEngine engine = new FakeFaceAiEngine()
                .withFaces(new DetectedFace(10, 10, 20, 40, 0.95f, sidewaysLandmarks()));

        run(engine);

        assertEquals(Long.valueOf(nameId), faceDao.findById(id).orElseThrow().nameId(),
                "straightening a crop must not lose the face's name");
    }

    @Test
    void run_countsACropThatCannotBeReEncodedAsFailed() throws Exception {
        long id = storeFace("img", 10, 10, 20, 40);
        imageSource.put("img", solidImage(100, 100));
        FakeFaceAiEngine engine = new FakeFaceAiEngine()
                .withFaces(new DetectedFace(10, 10, 20, 40, 0.95f, sidewaysLandmarks()))
                .withFailingEmbedding();

        FaceRotationMigration.Result result = run(engine);

        assertEquals(1, result.failed(), "a crop that cannot be re-encoded is reported");
        assertEquals(0, result.rotated());
        assertTrue(result.completed(), "one bad crop must not abort the whole pass");
        assertArrayEquals(OLD_JPEG, faceDao.findById(id).orElseThrow().subImageJpg(),
                "a failed rewrite leaves the stored crop untouched");
    }

    @Test
    void run_skipsAFaceWhoseOriginalFileIsGone() throws Exception {
        long id = storeFace("img", 10, 10, 20, 40);
        // No original registered for "img" in the image source.
        FakeFaceAiEngine engine = new FakeFaceAiEngine()
                .withFaces(new DetectedFace(10, 10, 20, 40, 0.95f, sidewaysLandmarks()));

        FaceRotationMigration.Result result = run(engine);

        assertEquals(1, result.skipped(), "a face without an original cannot be re-derived");
        assertEquals(0, result.rotated());
        assertEquals(0, engine.detectCalls(), "a missing original is never sent to detection");
        assertArrayEquals(OLD_JPEG, faceDao.findById(id).orElseThrow().subImageJpg(),
                "a face without an original keeps the crop it has");
    }

    @Test
    void run_skipsAFaceThatNoFreshDetectionMatches() throws Exception {
        long id = storeFace("img", 10, 10, 20, 40);
        imageSource.put("img", solidImage(100, 100));
        FakeFaceAiEngine engine = new FakeFaceAiEngine()
                .withFaces(new DetectedFace(60, 60, 20, 40, 0.95f, sidewaysLandmarks()));

        FaceRotationMigration.Result result = run(engine);

        assertEquals(1, result.skipped(),
                "a detection that does not overlap the stored box is a different face");
        assertArrayEquals(OLD_JPEG, faceDao.findById(id).orElseThrow().subImageJpg());
    }

    @Test
    void run_reportsProgressForEveryStoredFace() throws Exception {
        storeFace("img", 10, 10, 20, 40);
        storeFace("img", 50, 50, 20, 40);
        imageSource.put("img", solidImage(100, 100));
        FakeFaceAiEngine engine = new FakeFaceAiEngine().withFaces(
                new DetectedFace(10, 10, 20, 40, 0.95f, uprightLandmarks()),
                new DetectedFace(50, 50, 20, 40, 0.95f, uprightLandmarks()));
        List<String> progress = new ArrayList<>();

        try (FaceAiService service = new FaceAiService(engine);
             FaceRotationMigration migration = migration()) {
            migration.run(service, (processed, total) -> progress.add(processed + "/" + total),
                    () -> false);
        }

        assertEquals(List.of("1/2", "2/2"), progress,
                "progress is reported once per stored face, with the running total");
    }

    @Test
    void run_groupsTheFacesOfOneImageIntoASingleDetectionPass() throws Exception {
        storeFace("img", 10, 10, 20, 40);
        storeFace("img", 50, 50, 20, 40);
        imageSource.put("img", solidImage(100, 100));
        FakeFaceAiEngine engine = new FakeFaceAiEngine().withFaces(
                new DetectedFace(10, 10, 20, 40, 0.95f, uprightLandmarks()),
                new DetectedFace(50, 50, 20, 40, 0.95f, uprightLandmarks()));

        run(engine);

        assertEquals(1, engine.detectCalls(), "an image with many faces is detected once");
    }

    @Test
    void run_whenCancelled_doesNotRecordCompletion() throws Exception {
        storeFace("img", 10, 10, 20, 40);
        imageSource.put("img", solidImage(100, 100));
        FakeFaceAiEngine engine = new FakeFaceAiEngine()
                .withFaces(new DetectedFace(10, 10, 20, 40, 0.95f, sidewaysLandmarks()));

        FaceRotationMigration.Result result;
        try (FaceAiService service = new FaceAiService(engine);
             FaceRotationMigration migration = migration()) {
            result = migration.run(service, (processed, total) -> { }, () -> true);

            assertFalse(migration.isCompleted(),
                    "an interrupted pass must be retried on the next start");
        }

        assertFalse(result.completed());
        assertEquals(0, result.rotated(), "nothing is rewritten once the pass is cancelled");
    }

    @Test
    void isCompleted_isFalseUntilAPassFinishes() throws Exception {
        imageSource.put("img", solidImage(100, 100));
        FakeFaceAiEngine engine = new FakeFaceAiEngine();

        try (FaceAiService service = new FaceAiService(engine);
             FaceRotationMigration migration = migration()) {
            assertFalse(migration.isCompleted(), "a fresh library has never been migrated");

            migration.run(service, (processed, total) -> { }, () -> false);

            assertTrue(migration.isCompleted(),
                    "a finished pass must not run again on the next start");
        }
    }

    @Test
    void close_closesTheImageSource() {
        migration().close();

        assertTrue(imageSource.closed, "the migration owns the image source it was given");
    }

    @Test
    void intersectionOverUnion_measuresOverlap() {
        FaceDao.FaceBbox stored = new FaceDao.FaceBbox(1, "img", 0, 0, 10, 10);

        assertEquals(1.0, FaceRotationMigration.intersectionOverUnion(
                stored, new DetectedFace(0, 0, 10, 10, 0.9f)), 1e-9,
                "identical boxes overlap completely");
        assertEquals(0.0, FaceRotationMigration.intersectionOverUnion(
                stored, new DetectedFace(20, 20, 10, 10, 0.9f)), 1e-9,
                "disjoint boxes do not overlap");
        assertEquals(50.0 / 250.0, FaceRotationMigration.intersectionOverUnion(
                stored, new DetectedFace(5, 0, 20, 10, 0.9f)), 1e-9,
                "half of the stored box overlaps a twice-as-wide box");
        assertEquals(0.25, FaceRotationMigration.intersectionOverUnion(
                stored, new DetectedFace(6, 0, 10, 10, 0.9f)), 1e-9,
                "a shifted neighbouring face stays below the match threshold");
    }

    private FaceRotationMigration.Result run(FakeFaceAiEngine engine) throws Exception {
        try (FaceAiService service = new FaceAiService(engine);
             FaceRotationMigration migration = migration()) {
            return migration.run(service, (processed, total) -> { }, () -> false);
        }
    }

    private FaceRotationMigration migration() {
        return new FaceRotationMigration(faceDao, metaDao, imageSource, configStore);
    }

    private long storeFace(String imageHash, int x, int y, int width, int height) throws Exception {
        if (storedImages.add(imageHash)) {
            imageDao.insert(imageHash, 1L, "{}", 1);
        }
        return faceDao.insert(new FaceRecord(0, imageHash, x, y, width, height, 0.9f,
                OLD_EMBEDDING, OLD_JPEG, null));
    }

    /** A face standing upright: the mouth sits directly below the eyes. */
    private static float[] uprightLandmarks() {
        return new float[]{
                20, 20,   // left eye
                20, 30,   // right eye
                20, 35,   // nose tip
                19, 40,   // left mouth corner
                21, 40};  // right mouth corner
    }

    /** Eyes to the right of the mouth: the face was recorded a quarter turn clockwise. */
    private static float[] sidewaysLandmarks() {
        return new float[]{
                30, 30,   // left eye
                30, 40,   // right eye
                25, 35,   // nose tip
                20, 33,   // left mouth corner
                20, 37};  // right mouth corner
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

    /** In-memory originals, keyed by the hash the faces reference. */
    private static final class FakeImageSource implements FaceImageSource {

        private final Map<String, BufferedImage> images = new HashMap<>();
        private boolean closed;

        FakeImageSource put(String imageHash, BufferedImage image) {
            images.put(imageHash, image);
            return this;
        }

        @Override
        public BufferedImage load(String imageHash) {
            return images.get(imageHash);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
