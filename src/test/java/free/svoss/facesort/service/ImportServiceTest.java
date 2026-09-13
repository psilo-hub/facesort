package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end import tests over an in-memory database and a fake engine that
 * reports exactly one qualifying face per image.
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
        return new ImportService(imageDao, faceDao, new FaceAiService(engine), config());
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
}