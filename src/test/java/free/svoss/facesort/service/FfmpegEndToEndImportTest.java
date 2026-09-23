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

/**
 * End-to-end QA over the real {@link FfmpegVideoFrameSource}. A tiny
 * Motion-JPEG AVI is generated on the fly in pure Java (see
 * {@link TinyAviVideo}), so these tests exercise the native ffmpeg decode path
 * everywhere without binary fixtures and never silently skip.
 *
 * <p>Covers the video-import manual-QA scenario automatically: the first
 * import extracts frame images and detected faces, re-importing the same
 * folder is a no-op, and reopening the database over the same file (which
 * simulates an app restart) still skips the known video.</p>
 */
class FfmpegEndToEndImportTest {

    @TempDir
    Path tempDir;

    private ConfigModel config;
    private FaceAiService faceAiService;

    @BeforeEach
    void setUp() throws Exception {
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
    void generatedAvi_importsFrames_thenSkipsOnReimportAndRestart() throws Exception {
        Path folder = Files.createDirectory(tempDir.resolve("media"));
        TinyAviVideo.write(folder.resolve("sample.avi"), 64, 48, 5);
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

        // Reopen the database again, simulating an app restart.
        VideoImportService.VideoImportResult afterRestart = importWith(dbFile, folder);
        assertEquals(1, afterRestart.skipped(), "restart must skip the known video");
        assertEquals(0, afterRestart.newVideos());
        assertEquals(0, afterRestart.errors());
    }

    @Test
    void unreadableVideo_failsCleanlyInsteadOfCrashing() throws Exception {
        Path broken = tempDir.resolve("broken.mp4");
        Files.writeString(broken, "definitely not a video file");
        assertThrows(IOException.class, () -> new FfmpegVideoFrameSource(broken));
    }

    private VideoImportService.VideoImportResult importWith(Path dbFile, Path folder)
            throws Exception {
        Database db = new Database(dbFile);
        try {
            VideoImportService service = new VideoImportService(
                    new ImageDao(db.getConnection()),
                    new FaceDao(db.getConnection()),
                    new VideoDao(db.getConnection()),
                    List.of(faceAiService), config, db.getTransactionRunner());
            return service.importFolder(folder, null, () -> false);
        } finally {
            db.close();
        }
    }
}