package free.svoss.facesort.db;

import free.svoss.facesort.model.ImageRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ImageDao} against an in-memory SQLite database.
 */
class ImageDaoTest {

    private Database db;
    private ImageDao dao;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        dao = new ImageDao(db.getConnection());
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private void insertImage(String hash, int faceCount) throws Exception {
        dao.insert(hash, 123L, "{}", faceCount);
    }

    @Test
    void insert_exists_findByHash_roundTrip() throws Exception {
        insertImage("hash1", 2);

        assertTrue(dao.exists("hash1"), "inserted image must exist");
        assertFalse(dao.exists("missing"), "unknown hash must not exist");

        ImageRecord record = dao.findByHash("hash1").orElseThrow();
        assertEquals("hash1", record.hash());
        assertEquals(123L, record.detectionTs());
        assertEquals("{}", record.criteriaJson());
        assertEquals(2, record.faceCount());
    }

    @Test
    void findByHash_missing_returnsEmpty() throws Exception {
        assertTrue(dao.findByHash("nope").isEmpty());
    }

    @Test
    void addPath_getPaths_roundTrip() throws Exception {
        insertImage("hash1", 0);
        dao.addPath("hash1", "/photos/a.jpg");
        dao.addPath("hash1", "/photos/b.jpg");
        dao.addPath("hash1", "/photos/a.jpg"); // duplicate -> INSERT OR IGNORE

        List<String> paths = dao.getPaths("hash1");
        assertEquals(2, paths.size(), "duplicate path insert must be ignored");
        assertTrue(paths.contains("/photos/a.jpg"));
        assertTrue(paths.contains("/photos/b.jpg"));

        assertTrue(dao.getPaths("missing").isEmpty());
    }

    @Test
    void getAllHashes_returnsAllInserted() throws Exception {
        insertImage("h1", 0);
        insertImage("h2", 0);
        List<String> hashes = dao.getAllHashes();
        assertEquals(2, hashes.size());
        assertTrue(hashes.contains("h1"));
        assertTrue(hashes.contains("h2"));
    }

    @Test
    void thumbnail_absentByDefault() throws Exception {
        insertImage("hash1", 0);
        assertFalse(dao.hasThumbnail("hash1"));
        assertNull(dao.getThumbnail("hash1"), "no thumbnail row -> null bytes");
    }

    @Test
    void saveThumbnail_hasThumbnail_getThumbnail_roundTrip() throws Exception {
        insertImage("hash1", 0);
        byte[] jpeg = new byte[]{1, 2, 3, 4, 5};

        dao.saveThumbnail("hash1", jpeg);

        assertTrue(dao.hasThumbnail("hash1"));
        assertTrue(java.util.Arrays.equals(jpeg, dao.getThumbnail("hash1")));

        // replace works (INSERT OR REPLACE)
        byte[] jpeg2 = new byte[]{9, 9};
        dao.saveThumbnail("hash1", jpeg2);
        assertTrue(java.util.Arrays.equals(jpeg2, dao.getThumbnail("hash1")));
    }

    @Test
    void insertMissingReferencedImage_faceInsertFails() throws Exception {
        // FK guard: faces.image_hash references images(hash)
        FaceDao faceDao = new FaceDao(db.getConnection());
        org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class, () ->
                faceDao.insert(new free.svoss.facesort.model.FaceRecord(
                        0, "not-in-images", 0, 0, 10, 10, 0.9,
                        new float[]{1.0f}, new byte[]{4, 5}, null)));
    }
}