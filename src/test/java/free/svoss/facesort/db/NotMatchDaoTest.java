package free.svoss.facesort.db;

import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link NotMatchDao} against an in-memory SQLite database.
 */
class NotMatchDaoTest {

    private Database db;
    private NotMatchDao dao;
    private NameDao nameDao;
    private FaceDao faceDao;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        dao = new NotMatchDao(db.getConnection());
        nameDao = new NameDao(db.getConnection());
        faceDao = new FaceDao(db.getConnection());
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private long insertName(String name) throws Exception {
        return nameDao.insert(name);
    }

    private long insertUnnamedFace(String imageHash) throws Exception {
        new ImageDao(db.getConnection()).insert(imageHash, 0, "{}", 1);
        return faceDao.insert(new FaceRecord(
                0, imageHash, 10, 10, 80, 80, 0.9, new float[]{1, 0}, new byte[]{1}, null));
    }

    @Test
    void insert_isNotMatch_perNameAndFace() throws Exception {
        long alice = insertName("Alice");
        long bob = insertName("Bob");
        long face = insertUnnamedFace("img1");

        dao.insert(face, alice);

        assertTrue(dao.isNotMatch(face, alice), "the recorded rejection must be found");
        assertFalse(dao.isNotMatch(face, bob), "a rejection of one name must not apply to another");
        assertFalse(dao.isNotMatch(insertUnnamedFace("img2"), alice),
                "a rejection of one face must not apply to another face");
    }

    @Test
    void insert_twice_staysOneRow() throws Exception {
        long alice = insertName("Alice");
        long face = insertUnnamedFace("img1");

        dao.insert(face, alice);
        dao.insert(face, alice);

        assertEquals(Set.of(face), dao.findFaceIdsByNameId(alice),
                "re-recording the same decision must not create a duplicate row");
    }

    @Test
    void findFaceIdsByNameId_returnsOnlyThatNamesRejections() throws Exception {
        long alice = insertName("Alice");
        long bob = insertName("Bob");
        long rejectedForAlice = insertUnnamedFace("img1");
        long rejectedForBob = insertUnnamedFace("img2");
        long untouched = insertUnnamedFace("img3");

        dao.insert(rejectedForAlice, alice);
        dao.insert(rejectedForBob, bob);

        assertEquals(Set.of(rejectedForAlice), dao.findFaceIdsByNameId(alice));
        assertEquals(Set.of(rejectedForBob), dao.findFaceIdsByNameId(bob));
        assertTrue(dao.findFaceIdsByNameId(insertName("Carol")).isEmpty(),
                "a name without rejections gets an empty set");
        assertFalse(dao.findFaceIdsByNameId(alice).contains(untouched),
                "an undecided face must stay a candidate");
    }

    @Test
    void deletingTheFace_removesTheRejection() throws Exception {
        long alice = insertName("Alice");
        long face = insertUnnamedFace("img1");
        dao.insert(face, alice);

        try (Statement stmt = db.getConnection().createStatement()) {
            stmt.executeUpdate("DELETE FROM faces WHERE id = " + face);
        }

        assertFalse(dao.isNotMatch(face, alice),
                "the rejection must not outlive the face it belongs to");
    }

    @Test
    void deletingTheName_removesTheRejection() throws Exception {
        long alice = insertName("Alice");
        long face = insertUnnamedFace("img1");
        dao.insert(face, alice);

        nameDao.delete(alice);

        assertFalse(dao.isNotMatch(face, alice),
                "the rejection must not outlive the name it belongs to");
    }
}
