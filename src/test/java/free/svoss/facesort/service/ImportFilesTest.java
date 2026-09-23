package free.svoss.facesort.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the shared {@link ImportFiles} recursive file collection used by
 * both the photo and video import services.
 */
class ImportFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void collect_filtersByExtensionAndWalksRecursively() throws Exception {
        Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("notes.txt"), "not media");
        Files.writeString(tempDir.resolve("photo.jpg"), "jpg");
        Files.writeString(tempDir.resolve("sub/frame.png"), "png");
        Files.writeString(tempDir.resolve("sub/clip.mp4"), "mp4");

        List<Path> files = ImportFiles.collect(tempDir, Set.of("jpg", "png"), null);

        assertEquals(2, files.size());
        assertTrue(files.contains(tempDir.resolve("photo.jpg")));
        assertTrue(files.contains(tempDir.resolve("sub/frame.png")));
    }

    @Test
    void collect_returnsAbsolutelyNothingWhenCancelledUpfront() throws Exception {
        Files.writeString(tempDir.resolve("a.png"), "a");

        List<Path> files = ImportFiles.collect(tempDir, Set.of("png"), () -> true);

        assertTrue(files.isEmpty());
    }

    @Test
    void collect_stopsEarlyLeavingPartialListWhenCancelledMidWalk() throws Exception {
        for (int i = 0; i < 8; i++) {
            Files.writeString(tempDir.resolve("f" + i + ".png"), "f" + i);
        }
        AtomicInteger visited = new AtomicInteger();

        List<Path> files = ImportFiles.collect(tempDir, Set.of("png"),
                () -> visited.incrementAndGet() >= 4);

        assertFalse(files.isEmpty(), "the walk must have collected files before cancelling");
        assertTrue(files.size() < 8, "the walk must stop before visiting every file");
    }

    @Test
    void hasSupportedExtension_isCaseInsensitiveAndDotAware() {
        assertEquals(true, ImportFiles.hasSupportedExtension(Path.of("A.JPG"), Set.of("jpg")));
        assertEquals(false, ImportFiles.hasSupportedExtension(Path.of("no-extension"), Set.of("jpg")));
        assertEquals(false, ImportFiles.hasSupportedExtension(Path.of("x.png"), Set.of("jpg")));
    }
}