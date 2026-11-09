package free.svoss.facesort.service;

import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link UnnamedFacePool}: keeping the unnamed pool in memory
 * between scans and reloading when the database changes.
 */
class UnnamedFacePoolTest {

    private Database db;
    private FaceDao faceDao;
    private UnnamedFacePool pool;

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.inMemory();
        faceDao = new FaceDao(db.getConnection());
        pool = new UnnamedFacePool(faceDao);
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    private long addImageAndFace(String imageHash, float[] embedding, Long nameId) throws SQLException {
        new ImageDao(db.getConnection()).insert(imageHash, 0, "{}", 1);
        return faceDao.insert(new FaceRecord(
                0, imageHash, 10, 10, 80, 80, 0.9, embedding, new byte[]{1}, nameId));
    }

    private long addName() throws SQLException {
        return new NameDao(db.getConnection()).insert("Alice");
    }

    private static float[] xLike() {
        return new float[]{1, 0, 0, 0, 0, 0, 0, 0};
    }

    @Test
    void get_loadsUnnamedFacesLazily() throws SQLException {
        long alice = addName();
        addImageAndFace("imgU1", xLike(), null);
        addImageAndFace("imgU2", xLike(), null);
        addImageAndFace("imgNamed", xLike(), alice); // tagged, should be excluded

        List<FaceRecord> faces = pool.get();

        assertEquals(2, faces.size(),
                "only unnamed faces should be in the pool");
        assertEquals(2, pool.size(),
                "pool size should match");
    }

    @Test
    void get_reloadsWhenThePoolChanges() throws SQLException {
        addImageAndFace("imgU1", xLike(), null);
        addImageAndFace("imgU2", xLike(), null);

        List<FaceRecord> first = pool.get();
        assertEquals(2, first.size());

        // Tag one face externally: it leaves the unnamed pool.
        long alice = addName();
        faceDao.assignName(first.get(0).id(), alice);

        List<FaceRecord> second = pool.get();
        assertEquals(1, second.size(),
                "the pool should reload when the unnamed set changed");
        assertEquals(first.get(1).id(), second.get(0).id(),
                "the remaining unnamed face should still be in the pool");
    }

    @Test
    void get_doesNotReloadWhenThePoolIsUnchanged() throws SQLException {
        addImageAndFace("imgU1", xLike(), null);
        addImageAndFace("imgU2", xLike(), null);

        pool.get();
        int firstSize = pool.size();

        // No changes to the unnamed pool.
        pool.get();
        assertEquals(firstSize, pool.size(),
                "the pool should not reload when nothing changed");
    }

    @Test
    void remove_dropsAFaceFromThePool() throws SQLException {
        long u1 = addImageAndFace("imgU1", xLike(), null);
        addImageAndFace("imgU2", xLike(), null);

        pool.get();
        assertEquals(2, pool.size());

        pool.remove(u1);

        assertEquals(1, pool.size(),
                "removing a face should shrink the pool");
        assertTrue(pool.get().stream().noneMatch(f -> f.id() == u1),
                "the removed face should not be in the pool");
    }

    @Test
    void clear_forcesFullReload() throws SQLException {
        addImageAndFace("imgU1", xLike(), null);

        pool.get();
        assertEquals(1, pool.size());

        pool.clear();

        // After clear, the next get() reloads from the database.
        List<FaceRecord> reloaded = pool.get();
        assertEquals(1, reloaded.size(),
                "the pool should reload after clear");
    }

    @Test
    void get_excludesNotAFaceFaces() throws SQLException {
        long u1 = addImageAndFace("imgU1", xLike(), null);
        long u2 = addImageAndFace("imgU2", xLike(), null);
        faceDao.markNotAFace(u1);

        List<FaceRecord> faces = pool.get();

        assertEquals(1, faces.size(),
                "faces dismissed as not a face should be excluded");
        assertEquals(u2, faces.get(0).id());
    }
}
