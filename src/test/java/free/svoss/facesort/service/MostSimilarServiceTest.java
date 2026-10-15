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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for {@link MostSimilarService}: picking the single most
 * similar (name, unnamed face) pair of the library and applying the user's
 * three decisions to it.
 */
class MostSimilarServiceTest {

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
        configStore.updateAndGet(config -> config.withMinNameSimilarity(0.0)); // no cutoff in these tests
        FaceAiService faceAiService = new FaceAiService(new FakeFaceAiEngine());
        FaceToNameService faceToNameService = new FaceToNameService(faceAiService, faceDao,
                nameDao, notMatchDao, new ImageDao(db.getConnection()),
                new VideoDao(db.getConnection()), configStore);
        service = new MostSimilarService(faceToNameService, faceAiService, nameDao, notMatchDao);
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
    void findBestSuggestion_returnsTheGloballyMostSimilarPair() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgB1", yLike(), bob);
        long candidateForAlice = addImageAndFace("imgU1", xLike(), null);
        // Only moderately similar to Bob, so Alice's pair must win.
        addImageAndFace("imgU2", new float[]{0, 0.6f, 0.8f, 0, 0, 0, 0, 0}, null);

        Optional<MostSimilarService.Suggestion> best = service.findBestSuggestion();

        assertTrue(best.isPresent(), "a library with names and unnamed faces must yield a pair");
        assertEquals("Alice", best.get().name().name(),
                "the pair with the highest similarity in the whole library must be shown");
        assertEquals(candidateForAlice, best.get().candidate().id());
        assertEquals(1.0, best.get().nameSimilarity(), 1e-9,
                "the similarity must be the candidate's similarity to the name's average");
        assertEquals(1.0, best.get().faceSimilarity(), 1e-9,
                "the similarity between the two shown faces must be reported too");
    }

    @Test
    void findBestSuggestion_showsTheTaggedFaceClosestToTheAverage() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);   // least like the average
        addImageAndFace("imgA2", yLike(), alice);
        // Nearly parallel to the average of the three, so clearly the best match.
        long closest = addImageAndFace("imgA3", new float[]{0.2f, 0.98f, 0, 0, 0, 0, 0, 0}, alice);
        long candidate = addImageAndFace("imgU1", xLike(), null);

        Optional<MostSimilarService.Suggestion> best = service.findBestSuggestion();

        assertTrue(best.isPresent());
        assertEquals(closest, best.get().tagged().id(),
                "the tagged face shown must be the one most similar to the name's average");
        assertEquals(candidate, best.get().candidate().id());
    }

    @Test
    void findBestSuggestion_emptyWhenTheLibraryHasNoNamesOrNoUnnamedFaces() throws SQLException {
        assertTrue(service.findBestSuggestion().isEmpty(), "an empty library has no pair to offer");

        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);

        assertTrue(service.findBestSuggestion().isEmpty(),
                "a name without any unnamed face has no pair to offer");
    }

    @Test
    void findBestSuggestion_neverOffersARejectedCombination() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgB1", yLike(), bob);
        long rejectedForAlice = addImageAndFace("imgU1", xLike(), null);
        addImageAndFace("imgU2", new float[]{0, 0.6f, 0.8f, 0, 0, 0, 0, 0}, null);
        notMatchDao.insert(rejectedForAlice, alice);

        Optional<MostSimilarService.Suggestion> best = service.findBestSuggestion();

        assertTrue(best.isPresent(), "only Alice's best pair was rejected, Bob still has one");
        assertEquals("Bob", best.get().name().name(),
                "a rejected combination must not be shown again");
        assertTrue(best.get().candidate().id() != rejectedForAlice);
    }

    @Test
    void skip_hidesThePairForThisSessionWithoutPersistingIt() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgB1", yLike(), bob);
        long skippedFace = addImageAndFace("imgU1", xLike(), null);
        addImageAndFace("imgU2", new float[]{0, 0.6f, 0.8f, 0, 0, 0, 0, 0}, null);

        service.skip(skippedFace, alice);
        Optional<MostSimilarService.Suggestion> best = service.findBestSuggestion();

        assertEquals("Bob", best.orElseThrow().name().name(),
                "a skipped pair must not be shown again while the session lasts");
        assertTrue(notMatchDao.findFaceIdsByNameId(alice).isEmpty(),
                "skipping must not write a decision to the database");

        // A fresh service instance forgets session-only skips.
        MostSimilarService fresh = new MostSimilarService(
                new FaceToNameService(new FaceAiService(new FakeFaceAiEngine()), faceDao,
                        nameDao, notMatchDao, new ImageDao(db.getConnection()),
                        new VideoDao(db.getConnection()),
                        new ConfigStore(ConfigModel.defaults())),
                new FaceAiService(new FakeFaceAiEngine()), nameDao, notMatchDao);
        assertEquals("Alice", fresh.findBestSuggestion().orElseThrow().name().name(),
                "a skipped pair must come back in a new session");
    }

    @Test
    void markSamePerson_tagsTheFaceSoThePairMovesOn() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        long candidate = addImageAndFace("imgU1", xLike(), null);

        MostSimilarService.Suggestion best = service.findBestSuggestion().orElseThrow();
        service.markSamePerson(best.candidate().id(), best.name().id());

        assertEquals(Long.valueOf(alice), faceDao.findById(candidate).orElseThrow().nameId(),
                "'same person' must tag the unnamed face with the name");
        assertTrue(service.findBestSuggestion().isEmpty(),
                "the tagged face can no longer be suggested for any name");
    }

    @Test
    void markDifferentPerson_persistsTheRejectionAcrossInstances() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        long candidate = addImageAndFace("imgU1", xLike(), null);

        MostSimilarService.Suggestion best = service.findBestSuggestion().orElseThrow();
        service.markDifferentPerson(best.candidate().id(), best.name().id());

        assertTrue(service.findBestSuggestion().isEmpty(),
                "the rejected pair must be gone from this instance");
        assertTrue(notMatchDao.isNotMatch(candidate, alice),
                "'not the same person' must be stored in the database");
    }
}
