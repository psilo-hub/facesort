package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.config.ConfigStore;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.db.NotMatchDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic-library benchmark for {@link MostSimilarService}, plus the fixed
 * answer every optimisation must preserve.
 *
 * <p>The benchmark builds 100 names with four tagged faces each and 1000
 * unnamed faces of 512 dimensions, then measures one cold scan and five
 * "not the same person" decisions. The time is printed and checked against a
 * deliberately generous bound so slow CI machines do not turn it flaky.</p>
 */
class MostSimilarServiceBenchmarkTest {

    private static final int NAMES = 100;
    private static final int TAGGED_PER_NAME = 4;
    private static final int UNNAMED = 1000;
    private static final int DIMENSIONS = 512;
    private static final int DECISIONS = 5;
    private static final long MAX_BENCHMARK_MILLIS = 30_000;

    private Database db;
    private FaceDao faceDao;
    private NameDao nameDao;
    private NotMatchDao notMatchDao;
    private MostSimilarService service;

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.inMemory();
        faceDao = new FaceDao(db.getConnection());
        nameDao = new NameDao(db.getConnection());
        notMatchDao = new NotMatchDao(db.getConnection());
        ConfigStore configStore = new ConfigStore(ConfigModel.defaults());
        configStore.updateAndGet(config -> config.withMinNameSimilarity(0.0));
        FaceAiService ai = new FaceAiService(new FakeFaceAiEngine());
        service = new MostSimilarService(
                new FaceToNameService(ai, faceDao, nameDao, notMatchDao,
                        new ImageDao(db.getConnection()), new VideoDao(db.getConnection()), configStore),
                ai, nameDao, notMatchDao);
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    @Test
    void findBestSuggestion_stillReturnsTheFixedBestPair() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        float[] aliceDirection = new float[]{1, 0, 0, 0, 0, 0, 0, 0};
        addImageAndFace("namedAlice", aliceDirection, alice);
        addImageAndFace("namedBob", new float[]{0, 1, 0, 0, 0, 0, 0, 0}, bob);
        long expected = addImageAndFace("unnamed", aliceDirection, null);
        addImageAndFace("otherUnnamed", new float[]{0, 0.6f, 0.8f, 0, 0, 0, 0, 0}, null);

        MostSimilarService.Suggestion best = service.findBestSuggestion().orElseThrow();

        assertEquals("Alice", best.name().name());
        assertEquals(expected, best.candidate().id());
        assertEquals(1.0, best.nameSimilarity(), 1e-9);
        assertEquals(1.0, best.faceSimilarity(), 1e-9);
    }

    @Test
    void benchmark_coldScanAndFiveDecisionsStayWithinTheBudget() throws SQLException {
        seedLibrary();

        long scanStart = System.nanoTime();
        Optional<MostSimilarService.Suggestion> best = service.findBestSuggestion();
        long coldScanMillis = millisSince(scanStart);

        long decisionsStart = System.nanoTime();
        for (int i = 0; i < DECISIONS; i++) {
            MostSimilarService.Suggestion suggestion = best.orElseThrow();
            service.markDifferentPerson(suggestion.candidate().id(), suggestion.name().id());
            best = service.findBestSuggestion();
        }
        long decisionsMillis = millisSince(decisionsStart);

        long totalMillis = coldScanMillis + decisionsMillis;
        System.out.printf(
                "MostSimilar benchmark: cold scan %d ms, %d decisions %d ms "
                        + "(%.1f ms/decision), total %d ms [names=%d, tagged=%d, unnamed=%d, dims=%d]%n",
                coldScanMillis, DECISIONS, decisionsMillis,
                decisionsMillis / (double) DECISIONS, totalMillis,
                NAMES, NAMES * TAGGED_PER_NAME, UNNAMED, DIMENSIONS);

        assertTrue(best.isPresent(), "the synthetic library must keep offering pairs");
        assertTrue(totalMillis <= MAX_BENCHMARK_MILLIS,
                () -> "the synthetic scan and " + DECISIONS + " decisions took " + totalMillis
                        + " ms, above the " + MAX_BENCHMARK_MILLIS + " ms budget");
    }

    private void seedLibrary() throws SQLException {
        Random random = new Random(42);
        ImageDao imageDao = new ImageDao(db.getConnection());
        for (int nameIndex = 0; nameIndex < NAMES; nameIndex++) {
            long nameId = nameDao.insert("Person " + nameIndex);
            float[] base = unitVector(random);
            for (int faceIndex = 0; faceIndex < TAGGED_PER_NAME; faceIndex++) {
                imageDao.insert("named-" + nameIndex + "-" + faceIndex, 0, "{}", 1);
                faceDao.insert(new FaceRecord(0, "named-" + nameIndex + "-" + faceIndex,
                        0, 0, 80, 80, 0.9, near(base, random), new byte[]{1}, nameId));
            }
        }
        for (int faceIndex = 0; faceIndex < UNNAMED; faceIndex++) {
            String hash = "unnamed-" + faceIndex;
            imageDao.insert(hash, 0, "{}", 1);
            faceDao.insert(new FaceRecord(0, hash, 0, 0, 80, 80, 0.9,
                    unitVector(random), new byte[]{1}, null));
        }
    }

    private long addImageAndFace(String imageHash, float[] embedding, Long nameId) throws SQLException {
        new ImageDao(db.getConnection()).insert(imageHash, 0, "{}", 1);
        return faceDao.insert(new FaceRecord(
                0, imageHash, 10, 10, 80, 80, 0.9, embedding, new byte[]{1}, nameId));
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static float[] unitVector(Random random) {
        float[] vector = new float[DIMENSIONS];
        double norm = 0.0;
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) random.nextGaussian();
            norm += vector[i] * vector[i];
        }
        return scale(vector, 1.0 / Math.sqrt(norm));
    }

    private static float[] near(float[] base, Random random) {
        float[] varied = new float[DIMENSIONS];
        double norm = 0.0;
        for (int i = 0; i < varied.length; i++) {
            varied[i] = (float) (base[i] + random.nextGaussian() * 0.05);
            norm += varied[i] * varied[i];
        }
        return scale(varied, 1.0 / Math.sqrt(norm));
    }

    private static float[] scale(float[] vector, double factor) {
        float[] scaled = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            scaled[i] = (float) (vector[i] * factor);
        }
        return scaled;
    }
}
