package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for exporting all images of a selected person
 * ({@link FaceToNameService#exportImagesForName}).
 */
class FaceToNameServiceExportTest {

    private Database db;
    private FaceDao faceDao;
    private NameDao nameDao;
    private ImageDao imageDao;
    private FaceToNameService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.inMemory();
        faceDao = new FaceDao(db.getConnection());
        nameDao = new NameDao(db.getConnection());
        imageDao = new ImageDao(db.getConnection());
        service = new FaceToNameService(new FaceAiService(new FakeFaceAiEngine()),
                faceDao, nameDao, imageDao, new VideoDao(db.getConnection()), new ConfigModel());
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    private long addTaggedFace(String imageHash, long nameId) throws SQLException {
        if (!imageDao.exists(imageHash)) {
            imageDao.insert(imageHash, 0, "{}", 1);
        }
        return faceDao.insert(new FaceRecord(0, imageHash, 10, 10, 80, 80, 0.9,
                new float[]{1, 0, 0, 0, 0, 0, 0, 0}, new byte[]{1}, nameId));
    }

    private void addTaggedFaceAndPath(String imageHash, long nameId, Path file) throws SQLException {
        addTaggedFace(imageHash, nameId);
        imageDao.addPath(imageHash, file.toAbsolutePath().toString());
    }

    private static long fileCount(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    @Test
    void exportImagesForName_copiesExistingOriginalsIntoOutputFolder() throws Exception {
        long alice = nameDao.insert("Alice");
        Path src = Files.createDirectory(tempDir.resolve("src"));
        Path one = Files.writeString(src.resolve("one.jpg"), "one");
        Path two = Files.writeString(src.resolve("two.png"), "two");
        addTaggedFaceAndPath("img1", alice, one);
        addTaggedFaceAndPath("img2", alice, two);

        Path out = tempDir.resolve("out");
        FaceToNameService.ExportResult result = service.exportImagesForName(alice, out);

        assertTrue(Files.isDirectory(out), "output folder must be created");
        assertEquals(2, result.images());
        assertEquals(2, result.originalsCopied());
        assertEquals(0, result.thumbnailsCopied());
        assertEquals(0, result.missing());
        assertEquals("one", Files.readString(out.resolve("one.jpg")));
        assertEquals("two", Files.readString(out.resolve("two.png")));
    }

    @Test
    void exportImagesForName_exportsThumbnailWhenOriginalFileUnavailable() throws Exception {
        long alice = nameDao.insert("Alice");
        addTaggedFace("img1", alice);
        imageDao.addPath("img1", "/vanished/photo.JPG");
        byte[] thumb = new byte[]{1, 2, 3};
        imageDao.saveThumbnail("img1", thumb);

        Path out = tempDir.resolve("out");
        FaceToNameService.ExportResult result = service.exportImagesForName(alice, out);

        assertEquals(1, result.images());
        assertEquals(0, result.originalsCopied());
        assertEquals(1, result.thumbnailsCopied());
        assertEquals(0, result.missing());
        assertArrayEquals(thumb, Files.readAllBytes(out.resolve("photo.jpg")),
                "thumbnail must carry the stored path's base name with a .jpg extension");
    }

    @Test
    void exportImagesForName_usesHashNameForThumbnailWithoutStoredPath() throws Exception {
        long alice = nameDao.insert("Alice");
        addTaggedFace("img1", alice);
        byte[] thumb = new byte[]{4, 5, 6};
        imageDao.saveThumbnail("img1", thumb);

        Path out = tempDir.resolve("out");
        FaceToNameService.ExportResult result = service.exportImagesForName(alice, out);

        assertEquals(0, result.originalsCopied());
        assertEquals(1, result.thumbnailsCopied());
        assertArrayEquals(thumb, Files.readAllBytes(out.resolve("img1.jpg")));
    }

    @Test
    void exportImagesForName_exportsVideoFrameThumbnailWhenNoOriginal() throws Exception {
        long alice = nameDao.insert("Alice");
        imageDao.insert("frame1", 0, "{}", 1);
        byte[] thumb = new byte[]{7, 6, 5};
        imageDao.saveThumbnail("frame1", thumb);
        VideoDao videoDao = new VideoDao(db.getConnection());
        videoDao.insert("videoA", 0L, "{}", 10.0);
        videoDao.addPath("videoA", "/videos/family/party.mp4");
        videoDao.linkFrame("frame1", "videoA", 5000L);
        faceDao.insert(new FaceRecord(0, "frame1", 10, 10, 80, 80, 0.9,
                new float[]{1, 0, 0, 0, 0, 0, 0, 0}, new byte[]{1}, alice));

        Path out = tempDir.resolve("out");
        FaceToNameService.ExportResult result = service.exportImagesForName(alice, out);

        assertEquals(1, result.images());
        assertEquals(0, result.originalsCopied(), "the video frame has no photo original");
        assertEquals(1, result.thumbnailsCopied(), "the frame thumbnail must be exported");
        assertEquals(0, result.missing());
        assertArrayEquals(thumb, Files.readAllBytes(out.resolve("frame1.jpg")));
    }

    @Test
    void exportImagesForName_countsImagesWithoutPathOrThumbnailAsMissing() throws Exception {
        long alice = nameDao.insert("Alice");
        addTaggedFace("img1", alice);

        Path out = tempDir.resolve("out");
        FaceToNameService.ExportResult result = service.exportImagesForName(alice, out);

        assertEquals(1, result.images());
        assertEquals(1, result.missing());
        assertEquals(0, fileCount(out), "nothing can be exported for a completely unavailable image");
    }

    @Test
    void exportImagesForName_resolvesNameCollisionsWithNumericSuffixes() throws Exception {
        long alice = nameDao.insert("Alice");
        Path d1 = Files.createDirectory(tempDir.resolve("d1"));
        Path d2 = Files.createDirectory(tempDir.resolve("d2"));
        Path a = Files.writeString(d1.resolve("holiday.jpg"), "a");
        Path b = Files.writeString(d2.resolve("holiday.jpg"), "b");
        addTaggedFaceAndPath("img1", alice, a);
        addTaggedFaceAndPath("img2", alice, b);

        Path out = tempDir.resolve("out");
        FaceToNameService.ExportResult result = service.exportImagesForName(alice, out);

        assertEquals(2, result.originalsCopied());
        assertEquals("a", Files.readString(out.resolve("holiday.jpg")));
        assertEquals("b", Files.readString(out.resolve("holiday (1).jpg")));
    }

    @Test
    void exportImagesForName_exportsEachDistinctImageOnlyOnce() throws Exception {
        long alice = nameDao.insert("Alice");
        Path src = Files.createDirectory(tempDir.resolve("src"));
        Path group = Files.writeString(src.resolve("group.jpg"), "group");
        addTaggedFaceAndPath("img1", alice, group);
        faceDao.insert(new FaceRecord(0, "img1", 120, 10, 80, 80, 0.9,
                new float[]{1, 0, 0, 0, 0, 0, 0, 0}, new byte[]{1}, alice));

        Path out = tempDir.resolve("out");
        FaceToNameService.ExportResult result = service.exportImagesForName(alice, out);

        assertEquals(1, result.images(), "two faces from one image still export a single file");
        assertEquals(1, result.originalsCopied());
        assertEquals(1, fileCount(out));
    }

    @Test
    void exportImagesForName_returnsEmptyResultForNameWithoutFaces() throws Exception {
        long alice = nameDao.insert("Alice");

        Path out = tempDir.resolve("out");
        FaceToNameService.ExportResult result = service.exportImagesForName(alice, out);

        assertTrue(Files.isDirectory(out));
        assertEquals(0, result.images());
        assertEquals(0, result.originalsCopied());
        assertEquals(0, result.thumbnailsCopied());
        assertEquals(0, result.missing());
    }

    @Test
    void exportImagesForName_exportingIntoSourceFolderDoesNotFail() throws Exception {
        long alice = nameDao.insert("Alice");
        Path src = Files.createDirectory(tempDir.resolve("src"));
        Path photo = Files.writeString(src.resolve("photo.jpg"), "photo");
        addTaggedFaceAndPath("img1", alice, photo);

        FaceToNameService.ExportResult result = service.exportImagesForName(alice, src);

        assertEquals(1, result.images());
        assertEquals(1, result.originalsCopied(), "an image already in the target folder counts as exported");
        assertEquals(0, result.missing());
        assertFalse(Files.isDirectory(src.resolve("(1)")));
        assertEquals("photo", Files.readString(src.resolve("photo.jpg")));
    }
}