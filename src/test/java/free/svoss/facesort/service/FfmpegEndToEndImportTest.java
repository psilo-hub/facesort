package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.tools.faceai.DetectedFace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end QA over the real {@link FfmpegVideoFrameSource}. Requires the
 * bundled ffmpeg4j sample video (git-ignored under {@code ffmpeg4j-deps}); the
 * tests are skipped when it is absent.
 *
 * <p>Covers the video-import manual-QA scenario automatically: the first
 * import extracts frame images and detected faces, re-importing the same
 * folder is a no-op, and a fresh database over the same file (which simulates
 * an app restart) still skips the known video.</p>
 */
class FfmpegEndToEndImportTest {

    /** Valid H.264 MP4 shipped with the pinned ffmpeg4j module. */
    private static final Path SAMPLE =
            Path.of("ffmpeg4j-deps", "src", "test", "resources", "sample-mp4-file-small.mp4");

    /**
     * Local video (git-ignored) whose container header exposes no pixel format;
     * {@link FfmpegVideoFrameSource} must reject it with a clean IOException
     * instead of letting ffmpeg abort the JVM natively.
     */
    private static final Path NONE_FORMAT_SAMPLE =
            Path.of("test_media", "009862a2d2e3b01d.mp4");

    @TempDir
    Path tempDir;

    private ConfigModel config;
    private FaceAiService faceAiService;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "sample video missing: " + SAMPLE);
        config = new ConfigModel();
        config.setMinBoundingBoxSize(80);
        config.setMinConfidence(0.8);
        config.setMaxFacesPerImage(10);
        config.setThumbnailSize(256);
        faceAiService = new FaceAiService(new FakeFaceAiEngine()
                .withFaces(new DetectedFace(10, 10, 100, 100, 0.95f))
                .withEmbedding(new float[]{1, 0, 0, 0, 0, 0, 0, 0}));
    }

    @Test
    void realSampleMp4_importsFrames_thenSkipsOnReimportAndRestart() throws Exception {
        Path folder = Files.createDirectory(tempDir.resolve("media"));
        Files.copy(SAMPLE, folder.resolve("sample.mp4"));
        Path dbFile = tempDir.resolve("qa.db");

        VideoImportService.VideoImportResult first = importWith(dbFile, folder);
        assertEquals(1, first.totalVideos());
        assertEquals(1, first.newVideos());
        assertTrue(first.newFrames() > 0, "sample video must yield frame images");
        assertTrue(first.newFaces() > 0, "sample video faces must be detected");
        assertEquals(0, first.errors());

        // Re-import within the same database: known video + path -> skipped.
        VideoImportService.VideoImportResult second = importWith(dbFile, folder);
        assertEquals(1, second.skipped());
        assertEquals(0, second.newVideos());
        assertEquals(0, second.errors());

        // Simulate an app restart: fresh database over the same file.
        VideoImportService.VideoImportResult afterRestart = importWith(dbFile, folder);
        assertEquals(1, afterRestart.skipped(), "restart must skip the known video");
        assertEquals(0, afterRestart.newVideos());
        assertEquals(0, afterRestart.errors());
    }

    @Test
    void noneFormatVideo_failsCleanlyInsteadOfCrashing() throws Exception {
        assumeTrue(Files.exists(NONE_FORMAT_SAMPLE), "sample video missing: " + NONE_FORMAT_SAMPLE);
        assertThrows(IOException.class,
                () -> new FfmpegVideoFrameSource(NONE_FORMAT_SAMPLE));
    }

    private VideoImportService.VideoImportResult importWith(Path dbFile, Path folder)
            throws Exception {
        Database db = new Database(dbFile);
        try {
            VideoImportService service = new VideoImportService(
                    new ImageDao(db.getConnection()),
                    new FaceDao(db.getConnection()),
                    new VideoDao(db.getConnection()),
                    List.of(faceAiService), config);
            return service.importFolder(folder, null, () -> false);
        } finally {
            db.close();
        }
    }
}