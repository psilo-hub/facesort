package free.svoss.facesort.db;

import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link FaceDao} against an in-memory SQLite database.
 */
class FaceDaoTest {

    private static final byte[] JPEG = {1, 2, 3, 4, 5};

    private Database db;
    private ImageDao imageDao;
    private FaceDao faceDao;
    private NameDao nameDao;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        imageDao = new ImageDao(db.getConnection());
        faceDao = new FaceDao(db.getConnection());
        nameDao = new NameDao(db.getConnection());
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private void insertImage(String hash) throws Exception {
        imageDao.insert(hash, 123L, "{}", 4);
    }

    private long insertFace(String hash, int bboxX, int bboxY, float[] embedding, Long nameId) throws Exception {
        return faceDao.insert(new FaceRecord(
                0, hash, bboxX, bboxY, 10, 10, 0.9, embedding, JPEG, nameId));
    }

    @Test
    void insert_returnsGeneratedId() throws Exception {
        insertImage("img1");
        long id = insertFace("img1", 0, 0, new float[]{1.0f}, null);
        assertTrue(id > 0, "generated id must be positive");
        assertTrue(faceDao.findById(id).isPresent(), "inserted face must be findable by id");
    }

    @Test
    void findByImageHash_returnsMatchingFaces() throws Exception {
        insertImage("img1");
        insertImage("img2");
        insertFace("img1", 0, 0, new float[]{1.0f}, null);
        insertFace("img1", 20, 20, new float[]{2.0f}, null);
        insertFace("img2", 0, 0, new float[]{3.0f}, null);

        assertEquals(2, faceDao.findByImageHash("img1").size());
        assertEquals(1, faceDao.findByImageHash("img2").size());
    }

    @Test
    void findUnnamed_onlyReturnsUnnamedFaces() throws Exception {
        insertImage("img1");
        long nameId = nameDao.insert("Alice");
        insertFace("img1", 0, 0, new float[]{1.0f}, nameId);
        insertFace("img1", 20, 0, new float[]{2.0f}, null);
        insertFace("img1", 40, 0, new float[]{3.0f}, null);

        List<FaceRecord> unnamed = faceDao.findUnnamed();
        assertEquals(2, unnamed.size(), "only the two unnamed faces are returned");
        assertTrue(unnamed.stream().allMatch(f -> f.nameId() == null));
    }

    @Test
    void assignName_updatesFaceAndMovesBetweenQueries() throws Exception {
        insertImage("img1");
        long faceId = insertFace("img1", 0, 0, new float[]{1.0f}, null);
        long nameId = nameDao.insert("Alice");

        faceDao.assignName(faceId, nameId);

        assertEquals(nameId, faceDao.findById(faceId).orElseThrow().nameId());
        assertTrue(faceDao.findUnnamed().isEmpty(), "face is no longer unnamed");
        assertEquals(1, faceDao.findByNameId(nameId).size());
        assertEquals(1, faceDao.countByNameId(nameId));
    }

    @Test
    void findByNameId_filtersByName() throws Exception {
        insertImage("img1");
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        insertFace("img1", 0, 0, new float[]{1.0f}, alice);
        insertFace("img1", 20, 0, new float[]{2.0f}, alice);
        insertFace("img1", 40, 0, new float[]{3.0f}, bob);

        assertEquals(2, faceDao.findByNameId(alice).size());
        assertEquals(1, faceDao.findByNameId(bob).size());
        assertTrue(faceDao.findByNameId(alice).stream().allMatch(f -> f.nameId() == alice));
    }

    @Test
    void countByNameId_zeroForUnknownName() throws Exception {
        assertEquals(0, faceDao.countByNameId(999L));
    }

    @Test
    void reassignAll_movesAllFacesFromOneNameToAnother() throws Exception {
        insertImage("img1");
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        insertFace("img1", 0, 0, new float[]{1.0f}, alice);
        insertFace("img1", 20, 0, new float[]{2.0f}, alice);
        insertFace("img1", 40, 0, new float[]{3.0f}, null);

        faceDao.reassignAll(alice, bob);

        assertEquals(0, faceDao.countByNameId(alice), "Alice now has no faces");
        assertEquals(2, faceDao.countByNameId(bob), "Bob inherited Alice's faces");
        assertEquals(1, faceDao.findUnnamed().size(), "unnamed face untouched");
    }

    @Test
    void delete_removesFace() throws Exception {
        insertImage("img1");
        long faceId = insertFace("img1", 0, 0, new float[]{1.0f}, null);

        faceDao.delete(faceId);

        assertTrue(faceDao.findById(faceId).isEmpty());
        assertTrue(faceDao.findAll().isEmpty());
    }

    @Test
    void deleteName_setsFaceNameToNull() throws Exception {
        insertImage("img1");
        long alice = nameDao.insert("Alice");
        long faceId = insertFace("img1", 0, 0, new float[]{1.0f}, alice);

        nameDao.delete(alice);

        assertTrue(faceDao.findById(faceId).orElseThrow().nameId() == null,
                "faces.name_id must be SET NULL when the name row is deleted");
    }

    @Test
    void insert_embeddingRoundTripsViaEmbeddingUtils() throws Exception {
        insertImage("img1");
        float[] embedding = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f};
        insertFace("img1", 0, 0, embedding, null);

        FaceRecord loaded = faceDao.findAll().get(0);
        assertArrayEquals(embedding, loaded.embedding(), 0.0f,
                "embedding floats must survive the DB round-trip");
        assertArrayEquals(JPEG, loaded.subImageJpg());
    }

    @Test
    void insert_nullSubImageJpg_throws() throws Exception {
        insertImage("img1");
        FaceRecord bad = new FaceRecord(0, "img1", 0, 0, 10, 10, 0.9, new float[]{1.0f}, null, null);
        assertThrows(IllegalArgumentException.class, () -> faceDao.insert(bad),
                "NOT NULL sub_image_jpg must be rejected with a clear message");
    }

    @Test
    void insert_nullFace_throws() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> faceDao.insert(null));
    }

    @Test
    void findAll_returnsAllFaces() throws Exception {
        insertImage("img1");
        insertImage("img2");
        insertFace("img1", 0, 0, new float[]{1.0f}, null);
        insertFace("img1", 20, 0, new float[]{2.0f}, null);
        insertFace("img2", 0, 0, new float[]{3.0f}, null);

        assertEquals(3, faceDao.findAll().size());
    }
}