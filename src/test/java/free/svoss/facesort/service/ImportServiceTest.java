package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.tools.faceai.DetectedFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end import tests over an in-memory database and fake engines that
 * report exactly one qualifying face per image. Covers the single-engine
 * (sequential) path and the parallel multi-engine path.
 */
class ImportServiceTest {

    private Database db;
    private ImageDao imageDao;
    private FaceDao faceDao;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        imageDao = new ImageDao(db.getConnection());
        faceDao = new FaceDao(db.getConnection());
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private ConfigModel config() {
        ConfigModel config = new ConfigModel();
        config.setMinBoundingBoxSize(80);
        config.setMinConfidence(0.8);
        config.setMaxFacesPerImage(10);
        config.setThumbnailSize(256);
        return config;
    }

    private ImportService newImportService() {
        FakeFaceAiEngine engine = new FakeFaceAiEngine()
                .withFaces(new DetectedFace(10, 10, 100, 100, 0.95f))
                .withEmbedding(new float[]{1, 0, 0, 0, 0, 0, 0, 0});
        return new ImportService(imageDao, faceDao, new FaceAiService(engine), config(),
                db.getTransactionRunner());
    }

    private Path createPhotos(int count) throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("photos"));
        for (int i = 0; i < count; i++) {
            writePng(dir.resolve("photo" + i + ".png"), 200, 200, new Color(20 + i * 20, 40, 60));
        }
        return dir;
    }

    /**
     * Reports whether at least {@code count} images are already stored in the
     * in-memory database. Used as a cancellation predicate: import stops once
     * the given number of files has been imported.
     */
    private boolean importedAtLeast(int count) {
        try {
            return imageDao.getAllHashes().size() >= count;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void writePng(Path file, int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.WHITE);
            g.fillOval(60, 60, 80, 80);
        } finally {
            g.dispose();
        }
        assertTrue(ImageIO.write(image, "png", file.toFile()), "PNG write must succeed");
    }

    // ------------------------------------------------------------------
    // Fake engines for the parallel tests
    // ------------------------------------------------------------------

    private static final float[] TEST_EMBEDDING = {1, 0, 0, 0, 0, 0, 0, 0};

    private static DetectedFace[] testFaces() {
        return new DetectedFace[]{new DetectedFace(10, 10, 100, 100, 0.95f)};
    }

    /**
     * Engine that records the dimensions of every image offered to face
     * detection and the largest face crop handed to embedding. Used to verify
     * that high-resolution images are downscaled before detection and that
     * embedding never sees oversized face crops.
     */
    private static final class RecordingEngine extends FakeFaceAiEngine {

        private int detectionWidth;
        private int detectionHeight;
        private final AtomicInteger maxEmbeddingDim = new AtomicInteger();

        RecordingEngine(DetectedFace[] faces, float[] embedding) {
            withFaces(faces).withEmbedding(embedding);
        }

        int detectionWidth() {
            return detectionWidth;
        }

        int detectionHeight() {
            return detectionHeight;
        }

        int maxEmbeddingDim() {
            return maxEmbeddingDim.get();
        }

        @Override
        public synchronized DetectedFace[] detectFaces(BufferedImage image) {
            detectionWidth = image.getWidth();
            detectionHeight = image.getHeight();
            return super.detectFaces(image);
        }

        @Override
        public synchronized float[] getEmbedding(BufferedImage image) {
            int max = Math.max(image.getWidth(), image.getHeight());
            maxEmbeddingDim.accumulateAndGet(max, Math::max);
            return super.getEmbedding(image);
        }
    }

    /**
     * Engine that forces the worker threads to arrive at a common barrier
     * inside {@link #detectFaces}, proving the import is running concurrently.
     * The {@code active}/{@code overlapSeen} counters are shared by every
     * engine so that concurrency is measured across workers, not per engine.
     */
    private static final class BarrierEngine extends FakeFaceAiEngine {

        private final CyclicBarrier barrier;
        private final AtomicInteger active;
        private final AtomicInteger overlapSeen;

        BarrierEngine(CyclicBarrier barrier, AtomicInteger active, AtomicInteger overlapSeen,
                      DetectedFace[] faces, float[] embedding) {
            withFaces(faces).withEmbedding(embedding);
            this.barrier = barrier;
            this.active = active;
            this.overlapSeen = overlapSeen;
        }

        /** True when more than one worker was inside detectFaces at once. */
        boolean overlapSeen() {
            return overlapSeen.get() > 0;
        }

        @Override
        public DetectedFace[] detectFaces(BufferedImage image) {
            int concurrent = active.incrementAndGet();
            if (concurrent > 1) {
                overlapSeen.set(1);
            }
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException("Workers did not meet at the barrier", e);
            } finally {
                active.decrementAndGet();
            }
            return super.detectFaces(image);
        }
    }

    /**
     * Engine whose face detection takes a configurable amount of time and
     * reports the number of workers currently inside {@code detectFaces}
     * through shared counters. Used to verify both halves of the
     * {@code importFolder} contract without a wall clock: the workers really
     * overlap ({@code maxActive} reaches the worker count) and {@code importFolder}
     * joins every worker before it returns ({@code active} is back to zero when
     * the method returns, and the database already contains the full import).
     * The counters are shared across engines so concurrency is measured across
     * workers, not per engine.
     */
    private static final class SleepEngine extends FakeFaceAiEngine {

        private final long delayMs;
        private final AtomicInteger active;
        private final AtomicInteger maxActive;

        SleepEngine(long delayMs, AtomicInteger active, AtomicInteger maxActive) {
            withFaces(testFaces()).withEmbedding(TEST_EMBEDDING);
            this.delayMs = delayMs;
            this.active = active;
            this.maxActive = maxActive;
        }

        @Override
        public DetectedFace[] detectFaces(BufferedImage image) {
            int concurrent = active.incrementAndGet();
            maxActive.accumulateAndGet(concurrent, Math::max);
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return super.detectFaces(image);
        }
    }

    /**
     * Engine that holds every worker at a shared gate inside
     * {@link #detectFaces} until all configured engines have entered at least
     * once. Makes the "every configured engine must be used" assertion
     * deterministic: while a worker is blocked inside {@code detectFaces} it
     * cannot pull the next file from the shared counter, so the fixed pool is
     * forced to hand its first files to all engines before any of them finishes.
     */
    private static final class GatingEngine extends FakeFaceAiEngine {

        private final CountDownLatch gate;

        GatingEngine(CountDownLatch gate) {
            withFaces(testFaces()).withEmbedding(TEST_EMBEDDING);
            this.gate = gate;
        }

        @Override
        public DetectedFace[] detectFaces(BufferedImage image) {
            gate.countDown();
            try {
                if (!gate.await(10, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Not every configured engine entered face detection");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for the gate", e);
            }
            return super.detectFaces(image);
        }
    }

    /** Builds a service that spreads work over the given engines. */
    private ImportService parallelService(int threads, FakeFaceAiEngine... engines) {
        List<FaceAiService> services = new ArrayList<>();
        for (FakeFaceAiEngine engine : engines) {
            services.add(new FaceAiService(engine));
        }
        ConfigModel config = config();
        config.setMaxImportThreads(threads);
        return new ImportService(imageDao, faceDao, services, config, db.getTransactionRunner());
    }

    private static FakeFaceAiEngine countingEngine() {
        return new FakeFaceAiEngine().withFaces(testFaces()).withEmbedding(TEST_EMBEDDING);
    }

    private static BarrierEngine barrierEngine(CyclicBarrier barrier, AtomicInteger active,
                                               AtomicInteger overlapSeen) {
        return new BarrierEngine(barrier, active, overlapSeen, testFaces(), TEST_EMBEDDING);
    }

    /** Parses "Started|Completed N/M: file.png" into N. */
    private static int parseCompleted(String summary) {
        String[] parts = summary.split("\\s+");
        return Integer.parseInt(parts[1].substring(0, parts[1].indexOf('/')));
    }

    // ------------------------------------------------------------------
    // Existing sequential tests
    // ------------------------------------------------------------------

    @Test
    void importFolder_addsImagesFacesAndThumbnails() throws Exception {
        Path dir = createPhotos(2);
        try (ImportService service = newImportService()) {
            ImportService.ImportResult result = service.importFolder(dir, null);

            assertEquals(2, result.totalFiles());
            assertEquals(2, result.newImages());
            assertEquals(0, result.newPaths());
            assertEquals(2, result.newFaces());
            assertEquals(0, result.skipped());
            assertEquals(0, result.errors());

            assertEquals(2, imageDao.getAllHashes().size());
            assertEquals(2, faceDao.findUnnamed().size());
            for (String hash : imageDao.getAllHashes()) {
                assertTrue(imageDao.hasThumbnail(hash));
                assertNotNull(imageDao.getThumbnail(hash));
            }

            // A plain run completes: nothing cancelled, every file processed.
            assertFalse(result.wasCancelled());
            assertEquals(2, result.processed());
        }
    }

    @Test
    void importFolder_stopsWhenCancelledMidRun() throws Exception {
        Path dir = createPhotos(5);
        try (ImportService service = newImportService()) {
            // The supplier is consulted during the folder scan AND before each
            // file. It starts reporting true once 3 files are already imported,
            // so the run halts mid-way with state in the database.
            ImportService.ImportResult result = service.importFolder(dir, null,
                    () -> importedAtLeast(3));

            assertTrue(result.wasCancelled());
            assertEquals(5, result.totalFiles());
            assertEquals(3, result.processed());
            assertTrue(result.processed() < result.totalFiles());
            assertEquals(3, result.newImages());
            assertEquals(3, result.newFaces());
            assertEquals(0, result.errors());

            // Already-imported data must survive the cancellation.
            assertEquals(3, imageDao.getAllHashes().size());
            assertEquals(3, faceDao.findUnnamed().size());
            for (String hash : imageDao.getAllHashes()) {
                assertTrue(imageDao.hasThumbnail(hash));
                assertNotNull(imageDao.getThumbnail(hash));
            }
        }
    }

    @Test
    void importFolder_resumesAfterCancellationWithoutDuplicates() throws Exception {
        Path dir = createPhotos(5);
        try (ImportService service = newImportService()) {
            ImportService.ImportResult first = service.importFolder(dir, null,
                    () -> importedAtLeast(3));
            assertTrue(first.wasCancelled());
            assertEquals(3, first.processed());

            // A fresh, non-cancelled run imports the remaining files and skips
            // the already-known ones — the database ends with the full set.
            ImportService.ImportResult second = service.importFolder(dir, null);

            assertEquals(5, second.totalFiles());
            assertEquals(2, second.newImages());
            assertEquals(2, second.newFaces());
            assertEquals(3, second.skipped());
            assertEquals(0, second.newPaths());
            assertEquals(0, second.errors());
            assertFalse(second.wasCancelled());
            assertEquals(5, second.processed());

            assertEquals(5, imageDao.getAllHashes().size());
            assertEquals(5, faceDao.findUnnamed().size());
            for (String hash : imageDao.getAllHashes()) {
                assertTrue(imageDao.hasThumbnail(hash));
                assertNotNull(imageDao.getThumbnail(hash));
            }
        }
    }

    @Test
    void importFolder_skipsAlreadyKnownImagesAndPaths() throws Exception {
        Path dir = createPhotos(2);
        try (ImportService service = newImportService()) {
            service.importFolder(dir, null);

            ImportService.ImportResult second = service.importFolder(dir, null);

            assertEquals(2, second.totalFiles());
            assertEquals(0, second.newImages());
            assertEquals(0, second.newPaths());
            assertEquals(0, second.newFaces());
            assertEquals(2, second.skipped());
            assertEquals(0, second.errors());
            assertEquals(2, faceDao.findUnnamed().size());
        }
    }

    @Test
    void importFolder_recordsAdditionalPathForKnownImage() throws Exception {
        Path dir = createPhotos(1);
        try (ImportService service = newImportService()) {
            service.importFolder(dir, null);

            Path copyDir = Files.createDirectory(tempDir.resolve("copies"));
            Files.copy(dir.resolve("photo0.png"), copyDir.resolve("renamed.png"));
            ImportService.ImportResult result = service.importFolder(copyDir, null);

            assertEquals(1, result.totalFiles());
            assertEquals(0, result.newImages());
            assertEquals(1, result.newPaths());
            assertEquals(0, result.newFaces());
            assertEquals(0, result.errors());
            assertEquals(1, imageDao.getAllHashes().size());
        }
    }

    @Test
    void importFolder_ignoresNonImageFiles() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("mixed"));
        Files.writeString(dir.resolve("notes.txt"), "not an image");
        writePng(dir.resolve("real.png"), 200, 200, Color.BLUE);

        try (ImportService service = newImportService()) {
            ImportService.ImportResult result = service.importFolder(dir, null);

            assertEquals(1, result.totalFiles());
            assertEquals(1, result.newImages());
            assertEquals(0, result.errors());
        }
    }

    // ------------------------------------------------------------------
    // New parallel tests
    // ------------------------------------------------------------------

    @Test
    void importFolder_usesConfiguredThreadCountAndImportsEverything() throws Exception {
        Path dir = createPhotos(6);
        CountDownLatch gate = new CountDownLatch(3);
        GatingEngine engineA = new GatingEngine(gate);
        GatingEngine engineB = new GatingEngine(gate);
        GatingEngine engineC = new GatingEngine(gate);
        try (ImportService service = parallelService(3, engineA, engineB, engineC)) {
            ImportService.ImportResult result = service.importFolder(dir, null);

            assertFalse(result.wasCancelled());
            assertEquals(6, result.totalFiles());
            assertEquals(6, result.processed());
            assertEquals(6, result.newImages());
            assertEquals(6, result.newFaces());
            assertEquals(0, result.newPaths());
            assertEquals(0, result.skipped());
            assertEquals(0, result.errors());

            assertEquals(6, imageDao.getAllHashes().size());
            assertEquals(6, faceDao.findUnnamed().size());
            for (String hash : imageDao.getAllHashes()) {
                assertTrue(imageDao.hasThumbnail(hash));
                assertNotNull(imageDao.getThumbnail(hash));
            }
        }
        assertTrue(engineA.detectCalls() > 0, "every configured engine must be used");
        assertTrue(engineB.detectCalls() > 0, "every configured engine must be used");
        assertTrue(engineC.detectCalls() > 0, "every configured engine must be used");
    }

    @Test
    void importFolder_runsFilesConcurrently() throws Exception {
        Path dir = createPhotos(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger overlapSeen = new AtomicInteger();
        BarrierEngine engineA = barrierEngine(barrier, active, overlapSeen);
        BarrierEngine engineB = barrierEngine(barrier, active, overlapSeen);
        try (ImportService service = parallelService(2, engineA, engineB)) {
            ImportService.ImportResult result = service.importFolder(dir, null);

            // Both workers must have been inside detectFaces at the same time,
            // which is only possible when the import runs in parallel.
            assertTrue(engineA.overlapSeen() || engineB.overlapSeen());
            assertEquals(2, result.processed());
            assertEquals(2, result.newImages());
            assertEquals(0, result.errors());
            assertEquals(2, imageDao.getAllHashes().size());
        }
    }

    @Test
    void importFolder_parallelCancellationKeepsStateConsistent() throws Exception {
        Path dir = createPhotos(6);
        AtomicInteger seen = new AtomicInteger();
        FakeFaceAiEngine engineA = countingEngine();
        FakeFaceAiEngine engineB = countingEngine();
        FakeFaceAiEngine engineC = countingEngine();
        try (ImportService service = parallelService(3, engineA, engineB, engineC)) {
            ImportService.ImportResult result = service.importFolder(dir,
                    summary -> seen.set(parseCompleted(summary)),
                    () -> seen.get() >= 3);

            assertTrue(result.wasCancelled());
            assertEquals(6, result.totalFiles());
            assertTrue(result.processed() >= 3, "at least the first three files must be processed");
            assertTrue(result.processed() <= 5, "cancellation must stop before the last file");
            assertEquals(result.processed(), result.newImages());
            assertEquals(result.processed(), result.newFaces());
            assertEquals(0, result.errors());

            // Whatever was committed before cancellation must be consistent.
            assertEquals(result.processed(), imageDao.getAllHashes().size());
            assertEquals(result.processed(), faceDao.findUnnamed().size());
            for (String hash : imageDao.getAllHashes()) {
                assertTrue(imageDao.hasThumbnail(hash));
                assertNotNull(imageDao.getThumbnail(hash));
            }
        }
    }

    @Test
    void importFolder_parallelDuplicateContentRecordsNewPathWithoutErrors() throws Exception {
        Path dir = createPhotos(1);
        FakeFaceAiEngine engineA = countingEngine();
        FakeFaceAiEngine engineB = countingEngine();
        try (ImportService service = parallelService(2, engineA, engineB)) {
            service.importFolder(dir, null);

            Path copyDir = Files.createDirectory(tempDir.resolve("dup-copies"));
            Files.copy(dir.resolve("photo0.png"), copyDir.resolve("same.png"));
            ImportService.ImportResult result = service.importFolder(copyDir, null);

            assertFalse(result.wasCancelled());
            assertEquals(1, result.totalFiles());
            assertEquals(1, result.processed());
            assertEquals(0, result.newImages());
            assertEquals(1, result.newPaths());
            assertEquals(0, result.newFaces());
            assertEquals(0, result.errors());
            assertEquals(1, imageDao.getAllHashes().size());
        }
    }

    @Test
    void defaultConfig_hasFourImportThreads() {
        assertEquals(4, new ConfigModel().getMaxImportThreads());
        assertEquals(4, ConfigModel.DEFAULT_MAX_IMPORT_THREADS);
    }

    @Test
    void importFolder_returnsOnlyAfterAllWorkersFinished() throws Exception {
        Path dir = createPhotos(6);
        AtomicInteger sharedActive = new AtomicInteger();
        AtomicInteger sharedMaxActive = new AtomicInteger();
        SleepEngine e1 = new SleepEngine(40, sharedActive, sharedMaxActive);
        SleepEngine e2 = new SleepEngine(40, sharedActive, sharedMaxActive);
        ConfigModel c = config();
        c.setMaxImportThreads(2);
        ImportService.ImportResult result;
        try (ImportService service = new ImportService(imageDao, faceDao,
                List.of(new FaceAiService(e1), new FaceAiService(e2)), c,
                db.getTransactionRunner())) {
            result = service.importFolder(dir, null);
        }

        // Contract: importFolder returns only when every worker finished.
        assertEquals(6, result.totalFiles());
        assertEquals(6, result.processed());
        assertEquals(0, result.errors());
        assertEquals(0, sharedActive.get(),
                "no worker may still run after importFolder returns");
        assertEquals(2, sharedMaxActive.get(),
                "the two workers must really overlap (parallel, not serialized)");
        assertEquals(6, imageDao.getAllHashes().size());
        assertEquals(6, faceDao.findUnnamed().size());
        for (String hash : imageDao.getAllHashes()) {
            assertTrue(imageDao.hasThumbnail(hash));
        }
    }

    // ------------------------------------------------------------------
    // High-resolution import tests
    // ------------------------------------------------------------------

    @Test
    void importFolder_defaultMaxDetectionDimensionIsSane() {
        assertEquals(1600, new ConfigModel().getMaxDetectionDimension());
        assertEquals(1600, ConfigModel.DEFAULT_MAX_DETECTION_DIMENSION);
    }

    @Test
    void importFolder_scalesDownHighResolutionImageBeforeDetection() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("hi-res"));
        writePng(dir.resolve("large.png"), 1000, 800, Color.RED);

        // A face reported in detection coordinates (scale factor 0.5 here).
        RecordingEngine engine = new RecordingEngine(
                new DetectedFace[]{new DetectedFace(100, 100, 200, 200, 0.95f)},
                TEST_EMBEDDING);
        ConfigModel config = config();
        config.setMaxDetectionDimension(500);
        ImportService.ImportResult result;
        try (ImportService service = new ImportService(imageDao, faceDao,
                new FaceAiService(engine), config, db.getTransactionRunner())) {
            result = service.importFolder(dir, null);
        }

        assertEquals(1, result.newImages());
        assertEquals(1, result.newFaces());
        assertEquals(0, result.errors());
        assertEquals(1, imageDao.getAllHashes().size());
        assertTrue(imageDao.hasThumbnail(imageDao.getAllHashes().get(0)));

        // Detection ran on the scaled-down image (1000x800 -> 500x400).
        assertEquals(500, engine.detectionWidth());
        assertEquals(400, engine.detectionHeight());

        // Embedding never saw a face crop larger than the sub-image cap.
        assertTrue(engine.maxEmbeddingDim() <= 160,
                "embedding crop too large: " + engine.maxEmbeddingDim());

        // Stored bounding box is mapped back to original coordinates:
        // 100,100,200,200 at scale 0.5 -> 200,200,400,400.
        List<FaceRecord> faces = faceDao.findUnnamed();
        assertEquals(1, faces.size());
        assertEquals(200, faces.get(0).bboxX());
        assertEquals(200, faces.get(0).bboxY());
        assertEquals(400, faces.get(0).bboxW());
        assertEquals(400, faces.get(0).bboxH());
    }

    @Test
    void importFolder_keepsSmallImagesAtNativeResolution() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("small"));
        writePng(dir.resolve("small.png"), 200, 200, Color.GREEN);

        RecordingEngine engine = new RecordingEngine(testFaces(), TEST_EMBEDDING);
        ImportService.ImportResult result;
        try (ImportService service = new ImportService(imageDao, faceDao,
                new FaceAiService(engine), config(), db.getTransactionRunner())) {
            result = service.importFolder(dir, null);
        }

        assertEquals(0, result.errors());
        assertEquals(1, result.newImages());
        // Well below the default 1600px threshold: no downscaling.
        assertEquals(200, engine.detectionWidth());
        assertEquals(200, engine.detectionHeight());
        // And the crop (80,80 at 10,10 -> 100px) is capped at 160 for embedding.
        assertTrue(engine.maxEmbeddingDim() <= 160);
    }

    @Test
    void importFolder_surfacesLostFacesAsFileErrorsWithoutLosingTheImage() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("brokenface"));
        writePng(dir.resolve("photo.png"), 200, 200, Color.BLUE);

        FaceAiService failingService = new FaceAiService(new FakeFaceAiEngine()
                .withFaces(new DetectedFace(10, 10, 100, 100, 0.95f))
                .withFailingEmbedding());
        ImportService.ImportResult result;
        try (ImportService service = new ImportService(imageDao, faceDao,
                failingService, config(), db.getTransactionRunner())) {
            result = service.importFolder(dir, null);
        }

        assertEquals(1, result.newImages(), "the image is stored even though its faces were lost");
        assertEquals(0, result.newFaces(), "no face survived the failing embedding step");
        assertEquals(1, result.errors(), "lost faces are surfaced as a file error");
        assertEquals(0, result.skipped());
        assertEquals(1, result.processed());
    }
}
