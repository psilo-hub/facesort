package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.VideoFrameLinkRecord;
import free.svoss.facesort.model.VideoRecord;
import free.svoss.facesort.util.EmbeddingUtils;
import free.svoss.facesort.util.HashUtils;
import free.svoss.facesort.util.ImageUtils;
import free.svoss.tools.faceai.DetectedFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end video import tests over an in-memory database, a
 * {@link FakeFaceAiEngine} and an in-memory {@link VideoFrameSource} that
 * yields a few identical frames per sampled position. Covers the sequential
 * path, re-import deduplication, frames without faces, cancellation between
 * videos, per-file error resilience, the frame-hash collision case and the
 * parallel multi-engine path.
 */
class VideoImportServiceTest {

    private Database db;
    private ImageDao imageDao;
    private FaceDao faceDao;
    private VideoDao videoDao;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        imageDao = new ImageDao(db.getConnection());
        faceDao = new FaceDao(db.getConnection());
        videoDao = new VideoDao(db.getConnection());
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    // ------------------------------------------------------------------
    // Fakes and helpers
    // ------------------------------------------------------------------

    private static final float[] TEST_EMBEDDING = {1, 0, 0, 0, 0, 0, 0, 0};
    private static final DetectedFace[] ONE_FACE =
            {new DetectedFace(10, 10, 100, 100, 0.95f)};

    private ConfigModel config() {
        ConfigModel config = new ConfigModel();
        config.setMinBoundingBoxSize(80);
        config.setMinConfidence(0.8);
        config.setMaxFacesPerImage(10);
        config.setThumbnailSize(256);
        return config;
    }

    private VideoImportService newService(FaceAiService service) {
        return new VideoImportService(imageDao, faceDao, videoDao, List.of(service),
                config(), VideoImportServiceTest::openSource, db.getTransactionRunner());
    }

    private VideoImportService parallelService(int threads, FaceAiService... services) {
        ConfigModel config = config();
        config.setMaxImportThreads(threads);
        return new VideoImportService(imageDao, faceDao, videoDao, List.of(services),
                config, VideoImportServiceTest::openSource, db.getTransactionRunner());
    }

    /**
     * Fake "video file" opener: the file's first line is the duration; the
     * rest of the content is arbitrary and only there to distinguish files
     * with identical durations. A file whose first line is {@code "broken"}
     * fails to open so per-file error handling can be exercised.
     */
    private static VideoFrameSource openSource(Path file) throws IOException {
        String firstLine = Files.readString(file)
                .lines()
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElseThrow(() -> new IOException("empty fake video: " + file));
        if (firstLine.equalsIgnoreCase("broken")) {
            throw new IOException("cannot open video: " + file);
        }
        double duration;
        try {
            duration = Double.parseDouble(firstLine);
        } catch (NumberFormatException e) {
            throw new IOException("no duration in fake video: " + file, e);
        }
        return new FakeImportFrameSource(duration);
    }

    /**
     * In-memory {@link VideoFrameSource}: serves one solid 200x200 frame per
     * {@link FrameSampler} target, colored by a seed derived from the duration
     * — so two videos with the same duration produce byte-identical frames
     * (exercising the frame-hash collision path) while different durations
     * produce different frames. Seeking is forward-only like the real source.
     */
    private static final class FakeImportFrameSource implements VideoFrameSource {

        private final double duration;
        private final List<Double> positions;
        private int nextFrame;
        private double lastTarget = -1;

        FakeImportFrameSource(double duration) {
            this.duration = duration;
            this.positions = FrameSampler.sampleTargets(duration);
        }

        @Override
        public double getDuration() {
            return duration;
        }

        @Override
        public SampledFrame seekTo(double targetSeconds) {
            if (targetSeconds < lastTarget) {
                throw new IllegalStateException("seek must be forward-only");
            }
            lastTarget = targetSeconds;
            while (nextFrame < positions.size()
                    && positions.get(nextFrame) < targetSeconds) {
                nextFrame++;
            }
            if (nextFrame >= positions.size()) {
                return null;
            }
            double position = positions.get(nextFrame++);
            return new SampledFrame(frameImage(duration, position), position);
        }

        @Override
        public void close() {
            // nothing to release
        }

        /**
         * Builds the deterministic solid-color frame for (duration, position).
         * Colors are unique across every (duration, position) pair used by the
         * tests (durations 2–5 s, positions every second), so frames from
         * different videos never collide; identical (duration, position) still
         * yield byte-identical frames (the collision path).
         */
        public static BufferedImage frameImage(double duration, double position) {
            BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(frameColor(duration, position));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.dispose();
            return image;
        }

        /**
         * Deterministic gray: identical (duration, position) yield identical
         * colors, and no two pairs used by the tests map to the same gray.
         */
        private static Color frameColor(double duration, double position) {
            int gray = (int) Math.round(duration * 100 + position * 10) % 256;
            return new Color(gray, gray, gray);
        }
    }

    /** Engine that records how many frames it analysed. */
    private static final class CountingEngine implements FaceAiService.Engine {

        private final AtomicInteger detectCalls = new AtomicInteger();
        private final DetectedFace[] faces;
        private final float[] embedding;

        CountingEngine(DetectedFace[] faces, float[] embedding) {
            this.faces = faces;
            this.embedding = embedding;
        }

        int detectCalls() {
            return detectCalls.get();
        }

        @Override
        public DetectedFace[] detectFaces(BufferedImage image) {
            detectCalls.incrementAndGet();
            return faces;
        }

        @Override
        public float[] getEmbedding(BufferedImage image) {
            return embedding;
        }

        @Override
        public double calcSimilarity(float[] left, float[] right) {
            return EmbeddingUtils.cosineSimilarity(left, right);
        }

        @Override
        public float[] calcAverage(List<float[]> embeddings) {
            float[] average = new float[embeddings.get(0).length];
            for (float[] vector : embeddings) {
                for (int i = 0; i < average.length; i++) {
                    average[i] += vector[i];
                }
            }
            for (int i = 0; i < average.length; i++) {
                average[i] /= embeddings.size();
            }
            return average;
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    private Path createVideo(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    /**
     * JPEG bytes of the frame a fake video would emit for (duration, position),
     * encoded exactly like the service encodes frame hashes.
     */
    private static byte[] frameJpegBytes(double duration, double position) throws IOException {
        return ImageUtils.toJpegBytes(
                FakeImportFrameSource.frameImage(duration, position), 0.85f);
    }

    private FaceAiService oneFaceService() {
        return new FaceAiService(new FakeFaceAiEngine()
                .withFaces(ONE_FACE)
                .withEmbedding(TEST_EMBEDDING));
    }

    /** Parses "Completed N/M: file.mp4" into N. */
    private static int parseCompleted(String summary) {
        String[] parts = summary.split("\\s+");
        return Integer.parseInt(parts[1].substring(0, parts[1].indexOf('/')));
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    void importFolder_addsVideoFramesFacesAndThumbnails() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("videos"));
        Path file = createVideo(dir, "clip.mp4", "3.0\nsample");

        VideoImportService.VideoImportResult result;
        try (VideoImportService service = newService(oneFaceService())) {
            result = service.importFolder(dir, null);
        }

        assertEquals(1, result.totalVideos());
        assertEquals(1, result.newVideos());
        assertEquals(3, result.newFrames(), "3 s -> three sampled frames");
        assertEquals(3, result.newFaces(), "one face per frame");
        assertEquals(0, result.skipped());
        assertEquals(0, result.errors());
        assertFalse(result.wasCancelled());
        assertEquals(1, result.processed());

        String videoHash = HashUtils.hashFile(file);
        VideoRecord video = videoDao.findByHash(videoHash).orElseThrow();
        assertEquals(3.0, video.durationSecs(), 0.001);
        assertEquals(3, video.frameCount());
        assertEquals(3, video.faceCount());
        assertTrue(videoDao.getPaths(videoHash).contains(file.toAbsolutePath().toString()),
                "the video file path must be recorded");

        List<VideoFrameLinkRecord> links = videoDao.findFramesForVideo(videoHash);
        assertEquals(3, links.size());
        assertEquals(List.of(500L, 1500L, 2500L),
                links.stream().map(VideoFrameLinkRecord::timestampMs).toList(),
                "frame timestamps follow the sampled positions");

        assertEquals(3, imageDao.getAllHashes().size());
        for (VideoFrameLinkRecord link : links) {
            assertTrue(imageDao.hasThumbnail(link.frameHash()), "every frame keeps a thumbnail");
            assertNotNull(imageDao.getThumbnail(link.frameHash()));
            assertEquals(1, faceDao.findByImageHash(link.frameHash()).size(),
                    "each sampled frame carries its face");
        }
        assertEquals(3, faceDao.findUnnamed().size());
    }

    @Test
    void importFolder_skipsAlreadyKnownVideosAndPaths() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("videos"));
        createVideo(dir, "clip.mp4", "3.0\nsample");

        try (VideoImportService service = newService(oneFaceService())) {
            service.importFolder(dir, null);

            VideoImportService.VideoImportResult second = service.importFolder(dir, null);

            assertEquals(1, second.totalVideos());
            assertEquals(0, second.newVideos());
            assertEquals(0, second.newFrames());
            assertEquals(0, second.newFaces());
            assertEquals(1, second.skipped());
            assertEquals(0, second.errors());
            assertEquals(3, imageDao.getAllHashes().size(),
                    "the three frame rows from the first import are untouched");
            assertEquals(3, faceDao.findUnnamed().size());
        }
    }

    @Test
    void sameContentNewPath_onlyAddsAPath() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("videos"));
        Path copyDir = Files.createDirectory(tempDir.resolve("copies"));
        Path original = createVideo(dir, "clip.mp4", "3.0\nsample");
        Files.copy(original, copyDir.resolve("renamed.mp4"));

        try (VideoImportService service = newService(oneFaceService())) {
            VideoImportService.VideoImportResult first = service.importFolder(dir, null);
            assertEquals(1, first.newVideos());

            VideoImportService.VideoImportResult second = service.importFolder(copyDir, null);

            assertEquals(1, second.totalVideos());
            assertEquals(1, second.processed());
            assertEquals(0, second.newVideos());
            assertEquals(0, second.newFrames());
            assertEquals(0, second.newFaces());
            assertEquals(0, second.skipped(), "a new path was recorded, so the copy is not a plain skip");
            assertEquals(0, second.errors());
            assertEquals(3, imageDao.getAllHashes().size(),
                    "the copy introduces no duplicate frames");
        }
        assertEquals(2, videoDao.getPaths(HashUtils.hashFile(original)).size(),
                "both the original and the copy path must be recorded");
    }

    @Test
    void framesWithoutFaces_areStillStored() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("videos"));
        Path file = createVideo(dir, "empty.mp4", "2.0\nno faces");
        FakeFaceAiEngine engine = new FakeFaceAiEngine().withEmbedding(TEST_EMBEDDING);

        VideoImportService.VideoImportResult result;
        try (VideoImportService service = newService(new FaceAiService(engine))) {
            result = service.importFolder(dir, null);
        }

        assertEquals(1, result.newVideos());
        assertEquals(2, result.newFrames(), "2 s -> two sampled frames");
        assertEquals(0, result.newFaces());
        assertEquals(0, result.errors());

        VideoRecord video = videoDao.findByHash(HashUtils.hashFile(file)).orElseThrow();
        assertEquals(2, video.frameCount());
        assertEquals(0, video.faceCount());

        assertEquals(2, imageDao.getAllHashes().size());
        assertTrue(faceDao.findUnnamed().isEmpty(), "no faces were detected");
        for (String hash : imageDao.getAllHashes()) {
            assertTrue(imageDao.hasThumbnail(hash), "the frame thumbnail is kept even without faces");
            assertTrue(faceDao.findByImageHash(hash).isEmpty(),
                    "no faces qualified on this frame");
        }
    }

    @Test
    void importFolder_stopsBetweenVideosWhenCancelled() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("videos"));
        Path a = createVideo(dir, "a.mp4", "3.0\nA");
        createVideo(dir, "b.mp4", "2.0\nB");
        AtomicInteger completed = new AtomicInteger();

        try (VideoImportService service = newService(oneFaceService())) {
            VideoImportService.VideoImportResult result = service.importFolder(dir,
                    summary -> {
                        if (summary.startsWith("Completed")) {
                            completed.set(parseCompleted(summary));
                        }
                    },
                    () -> completed.get() >= 1);

            assertTrue(result.wasCancelled());
            assertEquals(2, result.totalVideos());
            assertEquals(1, result.processed());
            assertEquals(1, result.newVideos());
            assertEquals(0, result.errors());

            String hashA = HashUtils.hashFile(a);
            String hashB = HashUtils.hashFile(dir.resolve("b.mp4"));
            assertTrue(videoDao.exists(hashA) ^ videoDao.exists(hashB),
                    "exactly one of the two videos must be imported");

            String imported = videoDao.exists(hashA) ? hashA : hashB;
            int frames = imported.equals(hashA) ? 3 : 2;
            assertEquals(frames, result.newFrames());
            assertEquals(frames, result.newFaces());
            assertEquals(frames, videoDao.findFramesForVideo(imported).size());
            assertEquals(frames, imageDao.getAllHashes().size(),
                    "whatever was committed before cancellation must be consistent");
            assertEquals(frames, faceDao.findUnnamed().size());
        }
    }

    @Test
    void perFileErrors_areCountedAndDoNotAbortTheRest() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("videos"));
        createVideo(dir, "good.mp4", "3.0\nfine");
        createVideo(dir, "broken.mp4", "broken");

        try (VideoImportService service = newService(oneFaceService())) {
            VideoImportService.VideoImportResult result = service.importFolder(dir, null);

            assertFalse(result.wasCancelled());
            assertEquals(2, result.totalVideos());
            assertEquals(2, result.processed(), "both files are attempted");
            assertEquals(1, result.errors(), "the unreadable video fails cleanly");
            assertEquals(1, result.newVideos());
            assertEquals(3, result.newFrames());
            assertEquals(3, result.newFaces());

            // The good video is fully imported; the broken one left no rows behind.
            assertTrue(videoDao.exists(HashUtils.hashFile(dir.resolve("good.mp4"))));
            assertFalse(videoDao.exists(HashUtils.hashFile(dir.resolve("broken.mp4"))));
            assertEquals(3, imageDao.getAllHashes().size());
            assertEquals(3, faceDao.findUnnamed().size());
        }
    }

    @Test
    void existingFrameContent_keepsImageRowAndOnlyAddsLink() throws Exception {
        // A "photo" with byte-identical content to one of the video's frames is
        // already stored: the import must keep that image row, only add the
        // video_frames link, and not re-analyse or duplicate anything.
        double collisionPosition = 0.5;
        byte[] photoJpg = frameJpegBytes(3.0, collisionPosition);
        String photoHash = HashUtils.hashBytes(photoJpg);
        imageDao.insert(photoHash, System.currentTimeMillis(), "{}", 1);
        imageDao.addPath(photoHash, tempDir.resolve("photo.jpg").toAbsolutePath().toString());
        imageDao.saveThumbnail(photoHash, ImageUtils.toJpegBytes(
                ImageUtils.downsize(FakeImportFrameSource.frameImage(3.0, collisionPosition), 256),
                0.85f));
        faceDao.insert(new FaceRecord(0, photoHash, 10, 10, 100, 100, 0.95,
                TEST_EMBEDDING, photoJpg, null));

        Path dir = Files.createDirectory(tempDir.resolve("videos"));
        Path file = createVideo(dir, "clip.mp4", "3.0\nsample");

        VideoImportService.VideoImportResult result;
        try (VideoImportService service = newService(oneFaceService())) {
            result = service.importFolder(dir, null);
        }

        assertEquals(1, result.newVideos());
        assertEquals(2, result.newFrames(),
                "only the two frames that do not collide with the photo are new");
        assertEquals(2, result.newFaces(), "no re-detection of the existing frame");
        assertEquals(0, result.errors());

        String videoHash = HashUtils.hashFile(file);
        List<VideoFrameLinkRecord> links = videoDao.findFramesForVideo(videoHash);
        assertEquals(3, links.size(), "all three frames link to the video");
        assertEquals(photoHash, links.get(0).frameHash(),
                "the colliding frame reuses the existing photo row");
        assertEquals(500L, links.get(0).timestampMs(), "linked at the frame's own timestamp");

        assertEquals(1, faceDao.findByImageHash(photoHash).size(),
                "the photo's face is not duplicated by the video import");
        assertEquals(3, faceDao.findUnnamed().size(), "photo face + one face per new frame");
        assertEquals(3, imageDao.getAllHashes().size(),
                "photo + two new frames, each stored exactly once");
        assertEquals(3, videoDao.findByHash(videoHash).orElseThrow().frameCount());
        assertEquals(3, videoDao.findByHash(videoHash).orElseThrow().faceCount());
    }

    @Test
    void parallelMultiEngine_importsEverythingWithConfiguredThreads() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("videos"));
        createVideo(dir, "a.mp4", "2.0\nA");
        createVideo(dir, "b.mp4", "3.0\nB");
        createVideo(dir, "c.mp4", "4.0\nC");
        createVideo(dir, "d.mp4", "5.0\nD");
        CountingEngine engineA = new CountingEngine(ONE_FACE, TEST_EMBEDDING);
        CountingEngine engineB = new CountingEngine(ONE_FACE, TEST_EMBEDDING);

        try (VideoImportService service = parallelService(2,
                new FaceAiService(engineA), new FaceAiService(engineB))) {
            VideoImportService.VideoImportResult result = service.importFolder(dir, null);

            assertFalse(result.wasCancelled());
            assertEquals(4, result.totalVideos());
            assertEquals(4, result.processed());
            assertEquals(4, result.newVideos());
            assertEquals(14, result.newFrames(), "2 + 3 + 4 + 5 sampled frames");
            assertEquals(14, result.newFaces());
            assertEquals(0, result.errors());
            assertEquals(14, imageDao.getAllHashes().size());
            assertEquals(14, faceDao.findUnnamed().size());
        }
        assertTrue(engineA.detectCalls() > 0, "every configured engine must be used");
        assertTrue(engineB.detectCalls() > 0, "every configured engine must be used");
    }

    @Test
    void importFolder_surfacesLostFacesAsFileErrorsWithoutLosingFrames() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("brokenface"));
        createVideo(dir, "clip.mp4", "3.0\nsample");

        FaceAiService failingService = new FaceAiService(new FakeFaceAiEngine()
                .withFaces(ONE_FACE)
                .withFailingEmbedding());
        VideoImportService.VideoImportResult result;
        try (VideoImportService service = newService(failingService)) {
            result = service.importFolder(dir, null);
        }

        assertEquals(1, result.newVideos(), "the video is stored even though its faces were lost");
        assertEquals(3, result.newFrames(), "frames are still stored without faces");
        assertEquals(0, result.newFaces());
        assertEquals(1, result.errors(), "lost faces are surfaced as a file error");
        assertEquals(0, result.skipped());
    }
}