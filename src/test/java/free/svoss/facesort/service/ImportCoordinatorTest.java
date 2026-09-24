package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.tools.faceai.DetectedFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ImportCoordinator}, the two-phase runner behind the Import
 * tab: the image import runs first, the video import second, both stream into
 * the same progress listener and honor the same cancellation supplier.
 */
class ImportCoordinatorTest {

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

    private ConfigModel config() {
        ConfigModel config = new ConfigModel();
        config.setMinBoundingBoxSize(80);
        config.setMinConfidence(0.8);
        config.setMaxFacesPerImage(10);
        config.setThumbnailSize(256);
        return config;
    }

    private FaceAiService faceAiService() {
        return new FaceAiService(new FakeFaceAiEngine()
                .withFaces(new DetectedFace(10, 10, 100, 100, 0.95f))
                .withEmbedding(new float[]{1, 0, 0, 0, 0, 0, 0, 0}));
    }

    private ImportService imageService(FaceAiService service) {
        return new ImportService(imageDao, faceDao, List.of(service), config(),
                db.getTransactionRunner());
    }

    private VideoImportService videoService(FaceAiService service) {
        return new VideoImportService(imageDao, faceDao, videoDao, List.of(service),
                config(), ImportCoordinatorTest::openSource, db.getTransactionRunner());
    }

    /**
     * Fake "video file" opener mirroring {@link VideoImportServiceTest}: the
     * file's first line is the duration, served by an in-memory source that
     * yields one solid 200x200 frame per sampler target.
     */
    private static VideoFrameSource openSource(Path file) throws IOException {
        String firstLine = Files.readString(file)
                .lines()
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElseThrow(() -> new IOException("empty fake video: " + file));
        double duration = Double.parseDouble(firstLine);
        return new FakeImportFrameSource(duration);
    }

    /**
     * In-memory {@link VideoFrameSource}: one solid 200x200 frame per sampler
     * target, tinted by position so no two positions share a shade.
     */
    private static final class FakeImportFrameSource implements VideoFrameSource {

        private final double duration;
        private final List<Double> positions;
        private int nextFrame;

        FakeImportFrameSource(double duration) {
            this.duration = duration;
            this.positions = FrameSampler.sampleTargets(duration,
                    ConfigModel.DEFAULT_MAX_FRAMES_PER_VIDEO);
        }

        @Override
        public double getDuration() {
            return duration;
        }

        @Override
        public SampledFrame seekTo(double targetSeconds) {
            while (nextFrame < positions.size()
                    && positions.get(nextFrame) < targetSeconds) {
                nextFrame++;
            }
            if (nextFrame >= positions.size()) {
                return null;
            }
            double position = positions.get(nextFrame++);
            int gray = (int) Math.round(position * 97) % 256;
            BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(new Color(gray, gray, gray));
            g.fillRect(0, 0, 200, 200);
            g.dispose();
            return new SampledFrame(image, position);
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    private void writePng(Path file) throws Exception {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(20, 40, 60));
            g.fillRect(0, 0, 200, 200);
            g.setColor(Color.WHITE);
            g.fillOval(60, 60, 80, 80);
        } finally {
            g.dispose();
        }
        assertTrue(ImageIO.write(image, "png", file.toFile()), "PNG write must succeed");
    }

    private void writeFakeVideo(Path file) throws Exception {
        Files.writeString(file, "3.0\nfake clip");
    }

    @Test
    void run_importsImagesThenVideos_aggregatesBothSummaries() throws Exception {
        Path media = Files.createDirectory(tempDir.resolve("media"));
        writePng(media.resolve("shot.png"));
        writeFakeVideo(media.resolve("clip.mp4"));

        FaceAiService service = faceAiService();
        ImportCoordinator.CombinedImportResult combined = ImportCoordinator.run(
                imageService(service), videoService(service), media, null, () -> false, null);

        assertEquals(1, combined.images().totalFiles());
        assertEquals(1, combined.images().newImages());
        assertEquals(1, combined.videos().totalVideos());
        assertEquals(1, combined.videos().newVideos());
        assertTrue(combined.videos().newFrames() > 0);
        assertEquals(2, combined.total());
        assertEquals(2, combined.processed());
        assertFalse(combined.wasCancelled());
    }

    @Test
    void run_streamsBothPhasesIntoOneListener_inOrder() throws Exception {
        Path media = Files.createDirectory(tempDir.resolve("media"));
        writePng(media.resolve("shot.png"));
        writeFakeVideo(media.resolve("clip.mp4"));

        List<String> messages = new ArrayList<>();
        ImportService.ProgressListener progress = messages::add;
        Runnable beforeVideoPhase = () -> messages.add("VIDEO_PHASE_BEGIN");

        FaceAiService service = faceAiService();
        ImportCoordinator.run(imageService(service), videoService(service), media,
                progress, () -> false, beforeVideoPhase);

        int imageStart = indexOfStartsWith(messages, "Started 1/1: shot.png");
        int marker = messages.indexOf("VIDEO_PHASE_BEGIN");
        int videoStart = indexOfStartsWith(messages, "Started 1/1: clip.mp4");
        assertTrue(imageStart >= 0, "image phase must report progress");
        assertTrue(marker >= 0, "phase hook must fire");
        assertTrue(videoStart >= 0, "video phase must report progress");
        assertTrue(imageStart < marker, "image phase must precede the video phase");
        assertTrue(marker < videoStart, "video phase must follow the image phase");
    }

    @Test
    void run_cancellationBetweenPhases_stopsVideoPhaseCleanly() throws Exception {
        Path media = Files.createDirectory(tempDir.resolve("media"));
        writePng(media.resolve("shot.png"));
        writeFakeVideo(media.resolve("clip.mp4"));

        AtomicBoolean cancelled = new AtomicBoolean();
        FaceAiService service = faceAiService();
        ImportCoordinator.CombinedImportResult combined = ImportCoordinator.run(
                imageService(service), videoService(service), media, null,
                cancelled::get, () -> cancelled.set(true));

        assertFalse(combined.images().wasCancelled(), "image phase must finish first");
        assertTrue(combined.videos().wasCancelled(), "video phase must honor the shared flag");
        assertEquals(0, combined.videos().processed());
        assertEquals(1, combined.total());
        assertTrue(combined.wasCancelled());
    }

    @Test
    void run_cancellationBeforeStart_stopsBothPhases() throws Exception {
        Path media = Files.createDirectory(tempDir.resolve("media"));
        writePng(media.resolve("shot.png"));
        writeFakeVideo(media.resolve("clip.mp4"));

        FaceAiService service = faceAiService();
        ImportCoordinator.CombinedImportResult combined = ImportCoordinator.run(
                imageService(service), videoService(service), media, null, () -> true, null);

        assertTrue(combined.images().wasCancelled());
        assertTrue(combined.videos().wasCancelled());
        assertEquals(0, combined.images().totalFiles());
        assertEquals(0, combined.videos().totalVideos());
        assertEquals(0, combined.total());
    }

    private static int indexOfStartsWith(List<String> messages, String prefix) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}