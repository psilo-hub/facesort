package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.config.ConfigStore;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.db.NotMatchDao;
import free.svoss.facesort.db.QueryCountingConnection;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MostSimilarQueue}: serving prefetched pairs without a new
 * scan and dropping queued pairs that a decision or another tab made invalid.
 */
class MostSimilarQueueTest {

    private static final String UNNAMED_POOL_QUERY = "FROM faces WHERE name_id IS NULL";

    private Database db;
    private FaceDao faceDao;
    private NameDao nameDao;
    private NotMatchDao notMatchDao;
    private QueryCountingConnection counting;
    private MostSimilarService service;

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.inMemory();
        faceDao = new FaceDao(db.getConnection());
        nameDao = new NameDao(db.getConnection());
        notMatchDao = new NotMatchDao(db.getConnection());
        counting = QueryCountingConnection.around(db.getConnection());
        FaceDao countingDao = new FaceDao(counting.connection());
        ConfigStore configStore = new ConfigStore(ConfigModel.defaults());
        configStore.updateAndGet(config -> config.withMinNameSimilarity(0.0));
        FaceAiService ai = new FaceAiService(new FakeFaceAiEngine());
        service = new MostSimilarService(
                new FaceToNameService(ai, countingDao, nameDao, notMatchDao,
                        new ImageDao(db.getConnection()), new VideoDao(db.getConnection()), configStore),
                ai, countingDao, nameDao, notMatchDao, db.getTransactionRunner());
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

    private static float[] zLike() {
        return new float[]{0, 0, 1, 0, 0, 0, 0, 0};
    }

    /**
     * Builds three names with one tagged face each and three unnamed faces,
     * scored below the early-exit threshold so the scan yields all three pairs:
     * Carol (0.954), Bob (0.9), Alice (0.8).
     */
    private void addThreeRankedPairs() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        long carol = nameDao.insert("Carol");
        addImageAndFace("imgA", xLike(), alice);
        addImageAndFace("imgB", yLike(), bob);
        addImageAndFace("imgC", zLike(), carol);
        addImageAndFace("imgU1", new float[]{0.8f, 0.6f, 0, 0, 0, 0, 0, 0}, null);
        addImageAndFace("imgU2", new float[]{0, 0.9f, 0.43589f, 0, 0, 0, 0, 0}, null);
        addImageAndFace("imgU3", new float[]{0.3f, 0, 0.95394f, 0, 0, 0, 0, 0}, null);
    }

    private long unnamedPoolQueries() {
        return counting.queriesMatching(UNNAMED_POOL_QUERY);
    }

    @Test
    void next_servesQueuedPairsWithoutAnotherScan() throws SQLException {
        addThreeRankedPairs();
        MostSimilarQueue queue = new MostSimilarQueue(service, 2);

        MostSimilarService.Suggestion first = queue.next().orElseThrow();
        long scansAfterFirst = unnamedPoolQueries();
        MostSimilarService.Suggestion second = queue.next().orElseThrow();

        assertEquals(scansAfterFirst, unnamedPoolQueries(),
                "popping a queued pair must not rescan the unnamed pool: " + counting.statements());
        assertNotEquals(first.candidate().id(), second.candidate().id(),
                "the queued pair must be the next distinct candidate");
        assertEquals("Carol", first.name().name());
        assertEquals("Bob", second.name().name());
    }

    @Test
    void next_skipsAQueuedCandidateTaggedElsewhere() throws SQLException {
        addThreeRankedPairs();
        MostSimilarQueue queue = new MostSimilarQueue(service, 3);
        queue.next().orElseThrow();
        MostSimilarService.Suggestion stale = queue.queued().get(0);

        faceDao.assignName(stale.candidate().id(), stale.name().id());

        MostSimilarService.Suggestion next = queue.next().orElseThrow();
        assertFalse(samePair(next, stale), "a face tagged elsewhere must not be offered from the queue");
    }

    @Test
    void next_skipsAQueuedPairRejectedElsewhere() throws SQLException {
        addThreeRankedPairs();
        MostSimilarQueue queue = new MostSimilarQueue(service, 3);
        queue.next().orElseThrow();
        MostSimilarService.Suggestion stale = queue.queued().get(0);

        service.markDifferentPerson(stale.candidate().id(), stale.name().id());

        MostSimilarService.Suggestion next = queue.next().orElseThrow();
        assertFalse(samePair(next, stale), "a rejected pair must not be offered from the queue");
    }

    @Test
    void next_skipsASessionSkippedPair() throws SQLException {
        addThreeRankedPairs();
        MostSimilarQueue queue = new MostSimilarQueue(service, 3);
        queue.next().orElseThrow();
        MostSimilarService.Suggestion stale = queue.queued().get(0);

        service.skip(stale.candidate().id(), stale.name().id());

        MostSimilarService.Suggestion next = queue.next().orElseThrow();
        assertFalse(samePair(next, stale), "a skipped pair must not be offered from the queue");
    }

    @Test
    void prefetch_doesNotQueueTheCurrentlyShownPair() throws SQLException {
        addThreeRankedPairs();
        MostSimilarQueue queue = new MostSimilarQueue(service, 3);
        MostSimilarService.Suggestion current = queue.next().orElseThrow();

        queue.prefetch();

        assertFalse(queue.queued().stream().anyMatch(s -> samePair(s, current)),
                "the pair on screen must not be prefetched as the next one: " + queue.queued());
        assertTrue(queue.hasQueued(), "the prefetch must keep other pairs ready");
    }

    @Test
    void forgetPair_removesOnlyThatCombination() throws SQLException {
        addThreeRankedPairs();
        MostSimilarQueue queue = new MostSimilarQueue(service, 3);
        queue.next().orElseThrow();
        List<MostSimilarService.Suggestion> before = queue.queued();
        MostSimilarService.Suggestion forgotten = before.get(0);

        queue.forgetPair(forgotten.candidate().id(), forgotten.name().id());

        assertEquals(before.size() - 1, queue.queued().size());
        assertFalse(queue.queued().stream().anyMatch(s -> samePair(s, forgotten)));
    }

    private static boolean samePair(MostSimilarService.Suggestion a,
                                    MostSimilarService.Suggestion b) {
        return a.candidate().id() == b.candidate().id() && a.name().id() == b.name().id();
    }
}
