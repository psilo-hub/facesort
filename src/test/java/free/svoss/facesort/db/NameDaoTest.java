package free.svoss.facesort.db;

import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link NameDao} against an in-memory SQLite database.
 */
class NameDaoTest {

    private Database db;
    private NameDao dao;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        dao = new NameDao(db.getConnection());
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    @Test
    void insert_returnsGeneratedId() throws Exception {
        long id = dao.insert("Alice");
        assertTrue(id > 0, "generated id must be positive");
    }

    @Test
    void insert_findByName_roundTrip() throws Exception {
        long id = dao.insert("Alice");
        NameRecord record = dao.findByName("Alice").orElseThrow();
        assertEquals(id, record.id());
        assertEquals("Alice", record.name());
    }

    @Test
    void findById_roundTrip() throws Exception {
        long id = dao.insert("Bob");
        NameRecord record = dao.findById(id).orElseThrow();
        assertEquals("Bob", record.name());
    }

    @Test
    void findByName_missing_returnsEmpty() throws Exception {
        assertTrue(dao.findByName("Nobody").isEmpty());
        assertTrue(dao.findById(999L).isEmpty());
    }

    @Test
    void findAll_sortedByName() throws Exception {
        dao.insert("Charlie");
        dao.insert("Alice");
        dao.insert("Bob");

        List<NameRecord> all = dao.findAll();
        assertEquals(3, all.size());
        assertEquals(List.of("Alice", "Bob", "Charlie"),
                all.stream().map(NameRecord::name).toList(), "names must be ordered alphabetically");
    }

    @Test
    void insert_duplicateName_violatesUniqueConstraint() throws Exception {
        dao.insert("Alice");
        assertThrows(SQLException.class, () -> dao.insert("Alice"),
                "UNIQUE constraint on names.name must reject duplicates");
    }

    @Test
    void delete_removesName() throws Exception {
        long id = dao.insert("Alice");
        dao.delete(id);
        assertEquals(0, dao.count());
        assertTrue(dao.findById(id).isEmpty());
    }

    @Test
    void delete_setsFaceNameIdToNullWithoutDeletingFaces() throws Exception {
        long alice = dao.insert("Alice");
        ImageDao imageDao = new ImageDao(db.getConnection());
        FaceDao faceDao = new FaceDao(db.getConnection());
        imageDao.insert("imgA", 0, "{}", 1);
        long faceId = faceDao.insert(new FaceRecord(0, "imgA", 10, 10, 80, 80, 0.9,
                new float[]{1, 0, 0, 0, 0, 0, 0, 0}, new byte[]{1}, alice));

        dao.delete(alice);

        FaceRecord face = faceDao.findById(faceId).orElseThrow();
        assertNull(face.nameId(),
                "faces keep their image but must lose the deleted name reference");
        assertEquals(1, imageDao.getAllHashes().size(), "the image itself must survive");
    }

    @Test
    void rename_updatesNameInDatabase() throws Exception {
        long id = dao.insert("Alice");
        dao.insert("Bob");

        dao.rename(id, "Alicia");

        assertEquals("Alicia", dao.findById(id).orElseThrow().name());
        assertTrue(dao.findByName("Alice").isEmpty(), "old name must no longer exist");
        assertEquals("Bob", dao.findByName("Bob").orElseThrow().name());
    }

    @Test
    void rename_unknownId_throws() throws Exception {
        assertThrows(SQLException.class, () -> dao.rename(999L, "Nobody"));
    }

    @Test
    void rename_duplicateName_violatesUniqueConstraint() throws Exception {
        long alice = dao.insert("Alice");
        dao.insert("Bob");
        assertThrows(SQLException.class, () -> dao.rename(alice, "Bob"),
                "UNIQUE constraint on names.name must reject colliding renames");
    }

    @Test
    void count_tracksInsertsAndDeletes() throws Exception {
        assertEquals(0, dao.count());
        dao.insert("Alice");
        dao.insert("Bob");
        assertEquals(2, dao.count());
        dao.delete(dao.findByName("Alice").orElseThrow().id());
        assertEquals(1, dao.count());
    }
}