package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.config.ConfigStore;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link UnnamedFaceHnswIndex}: approximate nearest-neighbour
 * search over the unnamed face pool.
 */
class UnnamedFaceHnswIndexTest {

    private Database db;
    private FaceDao faceDao;
    private UnnamedFacePool pool;
    private UnnamedFaceHnswIndex index;

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.inMemory();
        faceDao = new FaceDao(db.getConnection());
        pool = new UnnamedFacePool(faceDao);
        ConfigStore configStore = new ConfigStore(ConfigModel.defaults());
        index = new UnnamedFaceHnswIndex(pool, configStore);
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

    private static float[] xLike() {
        return new float[]{1, 0, 0, 0, 0, 0, 0, 0};
    }

    private static float[] yLike() {
        return new float[]{0, 1, 0, 0, 0, 0, 0, 0};
    }

    @Test
    void query_returnsClosestFacesFirst() throws SQLException {
        // Query is x-like, so xLike faces should be closest.
        addImageAndFace("imgU1", xLike(), null);      // distance 0, identical
        addImageAndFace("imgU2", yLike(), null);      // distance sqrt(2), orthogonal
        addImageAndFace("imgU3", new float[]{0.8f, 0.6f, 0, 0, 0, 0, 0, 0}, null); // close to x

        index.ensureBuilt();

        List<Long> result = index.query(xLike(), 3);

        assertEquals(3, result.size(), "should return all 3 candidates");
        assertEquals(1L, result.get(0), "identical face should be closest");
    }

    @Test
    void query_excludesDeadFaces() throws SQLException {
        long u1 = addImageAndFace("imgU1", xLike(), null);
        addImageAndFace("imgU2", yLike(), null);

        index.ensureBuilt();
        List<Long> before = index.query(xLike(), 2);
        assertEquals(2, before.size(), "both faces should be returned before marking dead");

        index.markDead(u1);
        List<Long> after = index.query(xLike(), 2);
        assertEquals(1, after.size(), "dead face should be excluded");
        assertTrue(!after.contains(u1), "dead face id should not be in results");
    }

    @Test
    void query_emptyPool_returnsEmpty() throws SQLException {
        index.ensureBuilt();
        List<Long> result = index.query(xLike(), 5);
        assertTrue(result.isEmpty(), "empty pool should yield no results");
    }
}
