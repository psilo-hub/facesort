package free.svoss.facesort.service;

import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for {@link NameAverageCache}: each name's average embedding
 * is computed once and rebuilt whenever a face write can have changed it.
 */
class NameAverageCacheTest {

    private Database db;
    private FaceDao faceDao;
    private NameDao nameDao;
    private FakeFaceAiEngine engine;
    private NameAverageCache cache;

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.inMemory();
        faceDao = new FaceDao(db.getConnection());
        nameDao = new NameDao(db.getConnection());
        engine = new FakeFaceAiEngine();
        cache = new NameAverageCache(faceDao, nameDao, new FaceSelector(new FaceAiService(engine)));
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    private long addFace(String imageHash, float[] embedding, Long nameId) throws SQLException {
        new free.svoss.facesort.db.ImageDao(db.getConnection()).insert(imageHash, 0, "{}", 1);
        return faceDao.insert(new FaceRecord(
                0, imageHash, 10, 10, 80, 80, 0.9, embedding, new byte[]{1}, nameId));
    }

    private static float[] xLike() {
        return new float[]{1, 0, 0, 0, 0, 0, 0, 0};
    }

    private static float[] yLike() {
        return new float[]{0, 1, 0, 0, 0, 0, 0, 0};
    }

    private static final float[] MIXED = new float[]{0.5f, 0.5f, 0, 0, 0, 0, 0, 0};

    @Test
    void averageFor_returnsTheSameAverageAsAnUncachedComputation() throws SQLException {
        long alice = nameDao.insert("Alice");
        addFace("imgA1", xLike(), alice);
        addFace("imgA2", new float[]{0.2f, 0.8f, 0, 0, 0, 0, 0, 0}, alice);

        float[] cached = cache.averageFor(alice).orElseThrow();
        float[] uncached = new FaceSelector(new FaceAiService(new FakeFaceAiEngine()))
                .averageOf(faceDao.findByNameId(alice));

        assertArrayEquals(uncached, cached, 1e-6f);
    }

    @Test
    void averageFor_computesEachNameOnlyOnce() throws SQLException {
        long alice = nameDao.insert("Alice");
        addFace("imgA1", xLike(), alice);
        addFace("imgA2", xLike(), alice);

        cache.averageFor(alice);
        cache.averageFor(alice);

        assertEquals(1, engine.averageCalls(),
                "a cached name must not have its average recomputed");
    }

    @Test
    void averagesFor_computesOnlyTheMissingNames() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addFace("imgA1", xLike(), alice);
        addFace("imgB1", yLike(), bob);

        cache.averagesFor(List.of(alice));
        cache.averagesFor(List.of(alice, bob));

        assertEquals(2, engine.averageCalls(),
                "Alice's average must be reused and only Bob's computed");
    }

    @Test
    void averageFor_recomputesAfterAFaceIsTagged() throws SQLException {
        long alice = nameDao.insert("Alice");
        addFace("imgA1", xLike(), alice);
        long newFace = addFace("imgA2", yLike(), null);

        cache.averageFor(alice);
        faceDao.assignName(newFace, alice);

        assertArrayEquals(MIXED, cache.averageFor(alice).orElseThrow(), 1e-6f,
                "tagging a face through the DAO must invalidate the cached average");
        assertEquals(2, engine.averageCalls());
    }

    @Test
    void averageFor_recomputesAfterAnEmbeddingChanges() throws SQLException {
        long alice = nameDao.insert("Alice");
        long face = addFace("imgA1", xLike(), alice);

        cache.averageFor(alice);
        faceDao.updateSubImage(face, yLike(), new byte[]{2});

        assertArrayEquals(yLike(), cache.averageFor(alice).orElseThrow(), 1e-6f,
                "an embedding updated by the rotation migration must invalidate the cache");
        assertEquals(2, engine.averageCalls());
    }

    @Test
    void averageFor_recomputesAfterAFaceIsUntagged() throws SQLException {
        long alice = nameDao.insert("Alice");
        addFace("imgA1", xLike(), alice);
        addFace("imgA2", yLike(), alice);

        cache.averageFor(alice);
        faceDao.untagFacesFromImage("imgA1", alice);

        assertArrayEquals(yLike(), cache.averageFor(alice).orElseThrow(), 1e-6f,
                "untagging a face in another tab must invalidate the cached average");
        assertEquals(2, engine.averageCalls());
    }

    @Test
    void averageFor_recomputesBothNamesAfterAMerge() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addFace("imgA1", xLike(), alice);
        addFace("imgB1", yLike(), bob);

        cache.averageFor(alice);
        cache.averageFor(bob);
        faceDao.reassignAll(bob, alice);

        assertArrayEquals(MIXED, cache.averageFor(alice).orElseThrow(), 1e-6f,
                "a merge must invalidate the survivor's cached average");
        assertTrue(cache.averageFor(bob).isEmpty(),
                "the eliminated name must no longer have a cached average");
        assertEquals(3, engine.averageCalls());
    }

    @Test
    void averageFor_emptyWhenTheNameHasNoFaces() throws SQLException {
        long alice = nameDao.insert("Alice");

        Optional<float[]> average = cache.averageFor(alice);

        assertTrue(average.isEmpty());
        assertEquals(0, engine.averageCalls());
    }

    @Test
    void averageFor_storesComputedAverageToDatabase() throws SQLException {
        long alice = nameDao.insert("Alice");
        addFace("imgA1", xLike(), alice);

        cache.averageFor(alice);

        Optional<NameDao.NameAverage> stored = nameDao.loadNameAverage(alice);
        assertTrue(stored.isPresent(), "computed average must be persisted to the database");
        assertEquals(1, stored.get().faceCount(), "face count must match");
        assertArrayEquals(xLike(), stored.get().embedding(), 1e-6f,
                "stored embedding must match the computed average");
    }

    @Test
    void averageFor_loadsFromDatabaseOnNewCacheInstance() throws SQLException {
        long alice = nameDao.insert("Alice");
        addFace("imgA1", xLike(), alice);

        // Compute and store the average in the first cache instance.
        float[] first = cache.averageFor(alice).orElseThrow();

        // Simulate a restart: new cache instance, same database.
        NameAverageCache restarted = new NameAverageCache(
                faceDao, nameDao, new FaceSelector(new FaceAiService(new FakeFaceAiEngine())));
        float[] second = restarted.averageFor(alice).orElseThrow();

        assertArrayEquals(first, second, 1e-6f,
                "restarted cache must load the same average from the database");
    }

    @Test
    void averageFor_recomputesWhenStoredFaceCountChanged() throws SQLException {
        long alice = nameDao.insert("Alice");
        addFace("imgA1", xLike(), alice);

        cache.averageFor(alice);

        // Add another face externally, changing the face count.
        addFace("imgA2", yLike(), alice);

        // A new cache instance loads the stale stored average, detects the
        // face-count mismatch, and recomputes.
        NameAverageCache restarted = new NameAverageCache(
                faceDao, nameDao, new FaceSelector(new FaceAiService(new FakeFaceAiEngine())));
        float[] mixed = restarted.averageFor(alice).orElseThrow();

        assertArrayEquals(MIXED, mixed, 1e-6f,
                "stale stored average must be recomputed when face count changed");
    }

    @Test
    void averageFor_doesNotStoreWhenNameHasNoFaces() throws SQLException {
        long alice = nameDao.insert("Alice");

        assertTrue(cache.averageFor(alice).isEmpty());
        assertTrue(nameDao.loadNameAverage(alice).isEmpty(),
                "name with no faces must not store an average");
    }
}
