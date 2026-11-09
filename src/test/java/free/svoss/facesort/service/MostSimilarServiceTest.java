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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private ConfigStore configStore;
    private FakeFaceAiEngine engine;
    private MostSimilarService service;

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.inMemory();
        faceDao = new FaceDao(db.getConnection());
        nameDao = new NameDao(db.getConnection());
        notMatchDao = new NotMatchDao(db.getConnection());
        configStore = new ConfigStore(ConfigModel.defaults());
        configStore.updateAndGet(config -> config.withMinNameSimilarity(0.0)); // no cutoff in these tests
        engine = new FakeFaceAiEngine();
        FaceAiService faceAiService = new FaceAiService(engine);
        FaceToNameService faceToNameService = new FaceToNameService(faceAiService, faceDao,
                nameDao, notMatchDao, new ImageDao(db.getConnection()),
                new VideoDao(db.getConnection()), configStore);
        service = new MostSimilarService(faceToNameService, faceAiService, faceDao, nameDao, notMatchDao,
                db.getTransactionRunner());
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
    void findBestSuggestion_readsTheUnnamedPoolOnceForAllNames() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA", xLike(), alice);
        long candidate = addImageAndFace("imgU", xLike(), null);
        for (int i = 0; i < 5; i++) {
            long other = nameDao.insert("Other " + i);
            addImageAndFace("imgO" + i, xLike(), other);
        }
        QueryCountingConnection counting = QueryCountingConnection.around(db.getConnection());
        FaceDao countingDao = new FaceDao(counting.connection());
        ConfigStore configStore = new ConfigStore(ConfigModel.defaults());
        configStore.updateAndGet(config -> config.withMinNameSimilarity(0.0));
        FaceAiService ai = new FaceAiService(new FakeFaceAiEngine());
        MostSimilarService countingService = new MostSimilarService(
                new FaceToNameService(ai, countingDao, nameDao, notMatchDao,
                        new ImageDao(db.getConnection()), new VideoDao(db.getConnection()), configStore),
                ai, countingDao, nameDao, notMatchDao, db.getTransactionRunner());

        MostSimilarService.Suggestion best = countingService.findBestSuggestion().orElseThrow();

        assertEquals(candidate, best.candidate().id());
        assertEquals(1, counting.queriesMatching("FROM faces WHERE name_id IS NULL"),
                "the unnamed pool must be loaded once per scan, not once per name: "
                        + counting.statements());
    }

    @Test
    void findBestSuggestion_scoresSlimRowsAndHydratesOnlyTheShownPair() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgU1", xLike(), null);
        addImageAndFace("imgU2", yLike(), null);
        QueryCountingConnection counting = QueryCountingConnection.around(db.getConnection());
        FaceDao countingDao = new FaceDao(counting.connection());
        ConfigStore configStore = new ConfigStore(ConfigModel.defaults());
        configStore.updateAndGet(config -> config.withMinNameSimilarity(0.0));
        FaceAiService ai = new FaceAiService(new FakeFaceAiEngine());
        MostSimilarService countingService = new MostSimilarService(
                new FaceToNameService(ai, countingDao, nameDao, notMatchDao,
                        new ImageDao(db.getConnection()), new VideoDao(db.getConnection()), configStore),
                ai, countingDao, nameDao, notMatchDao, db.getTransactionRunner());

        MostSimilarService.Suggestion best = countingService.findBestSuggestion().orElseThrow();

        assertNotNull(best.tagged().subImageJpg(),
                "the tagged card still needs its crop");
        assertNotNull(best.candidate().subImageJpg(),
                "the candidate card still needs its crop");
        assertFalse(loadsEveryChosenRowWithItsCrop(counting.statements()),
                "the scan must score slim rows and hydrate only the shown pair: "
                        + counting.statements());
    }

    private static boolean loadsEveryChosenRowWithItsCrop(java.util.List<String> statements) {
        return statements.stream().anyMatch(sql -> sql.contains("sub_image_jpg")
                && (sql.contains("FROM faces WHERE name_id IS NULL")
                        || sql.contains("FROM faces WHERE name_id = ?")));
    }

    @Test
    void findBestSuggestion_scoresOnlyTheWinnersRepresentative() throws SQLException {
        float near = 0.9f;
        float side = (float) Math.sqrt(1.0 - near * near);
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        long carol = nameDao.insert("Carol");
        addImageAndFace("imgA", xLike(), alice);
        addImageAndFace("imgB", yLike(), bob);
        addImageAndFace("imgC", new float[]{0, 0, 1, 0, 0, 0, 0, 0}, carol);
        addImageAndFace("imgU1", new float[]{near, side, 0, 0, 0, 0, 0, 0}, null);
        addImageAndFace("imgU2", new float[]{side, near, 0, 0, 0, 0, 0, 0}, null);
        addImageAndFace("imgU3", new float[]{0, 0, near, side, 0, 0, 0, 0}, null);

        MostSimilarService.Suggestion best = service.findBestSuggestion().orElseThrow();

        assertEquals("Alice", best.name().name());
        assertEquals(near, best.nameSimilarity(), 1e-6,
                "the candidate's similarity to the winner's average must be unchanged");
        assertEquals(near, best.faceSimilarity(), 1e-6,
                "the similarity between the two shown faces must be unchanged");
        assertEquals(11, engine.similarityCalls(),
                "three candidates against three names, plus one representative and one shown pair: "
                        + "only the winning name may have its tagged faces scored");
    }

    @Test
    void findBestSuggestions_returnsDistinctCandidatesInDescendingOrder() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        long carol = nameDao.insert("Carol");
        addImageAndFace("imgA", xLike(), alice);
        addImageAndFace("imgB", yLike(), bob);
        addImageAndFace("imgC", new float[]{0, 0, 1, 0, 0, 0, 0, 0}, carol);
        long forAlice = addImageAndFace("imgU1", new float[]{0.8f, 0.6f, 0, 0, 0, 0, 0, 0}, null);
        long forBob = addImageAndFace("imgU2", new float[]{0, 0.9f, 0.43589f, 0, 0, 0, 0, 0}, null);
        long forCarol = addImageAndFace("imgU3", new float[]{0.3f, 0, 0.95394f, 0, 0, 0, 0, 0}, null);

        List<MostSimilarService.Suggestion> suggestions = service.findBestSuggestions(3);

        assertEquals(3, suggestions.size(), "the limit must yield the three best pairs");
        assertEquals(List.of(forCarol, forBob, forAlice),
                suggestions.stream().map(s -> s.candidate().id()).toList(),
                "pairs must be ordered by descending name similarity");
        assertTrue(suggestions.get(0).nameSimilarity() > suggestions.get(1).nameSimilarity());
        assertTrue(suggestions.get(1).nameSimilarity() > suggestions.get(2).nameSimilarity());
        suggestions.forEach(s -> {
            assertNotNull(s.tagged().subImageJpg(), "every returned pair must carry its tagged crop");
            assertNotNull(s.candidate().subImageJpg(), "every returned pair must carry its candidate crop");
        });
    }

    @Test
    void findBestSuggestions_keepsOnePairPerUnnamedFace() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addImageAndFace("imgA", xLike(), alice);
        addImageAndFace("imgB", xLike(), bob);
        long shared = addImageAndFace("imgU", new float[]{0.8f, 0.6f, 0, 0, 0, 0, 0, 0}, null);

        List<MostSimilarService.Suggestion> suggestions = service.findBestSuggestions(3);

        assertEquals(1, suggestions.size(),
                "a face must appear only once, paired with the name that scores it highest");
        assertEquals(shared, suggestions.get(0).candidate().id());
        assertEquals("Alice", suggestions.get(0).name().name(),
                "an equal score must keep the earlier name");
    }

    @Test
    void findBestSuggestions_returnsNothingForANonPositiveLimit() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA", xLike(), alice);
        addImageAndFace("imgU", xLike(), null);

        assertTrue(service.findBestSuggestions(0).isEmpty());
        assertTrue(service.findBestSuggestions(-1).isEmpty());
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
                new FaceAiService(new FakeFaceAiEngine()), faceDao, nameDao, notMatchDao,
                db.getTransactionRunner());
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
    void findBestSuggestion_reflectsAFaceTaggedAfterTheFirstScan() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgU1", xLike(), null);

        assertEquals(1.0, service.findBestSuggestion().orElseThrow().nameSimilarity(), 1e-9);

        // Another tab tags a face with Alice after the first scan.
        long extra = addImageAndFace("imgU2", yLike(), null);
        faceDao.assignName(extra, alice);

        assertEquals(1.0 / Math.sqrt(2.0),
                service.findBestSuggestion().orElseThrow().nameSimilarity(), 1e-9,
                "the next suggestion must use Alice's updated average embedding");
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

    @Test
    void findBestSuggestion_isDeterministicAcrossRepeatedRuns() throws SQLException {
        // Many names, several unnamed faces, some exact ties: parallel scoring
        // must converge to the same globally best pair every time.
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        long carol = nameDao.insert("Carol");
        addImageAndFace("imgA", xLike(), alice);
        addImageAndFace("imgB", yLike(), bob);
        addImageAndFace("imgC", yLike(), carol);
        // Alice's unnamed candidates are only ~0.6 similar to her average,
        // so she cannot win; Bob and Carol both score 1.0 against imgU1.
        long sharedBest = addImageAndFace("imgU1", yLike(), null);
        addImageAndFace("imgU2", new float[]{0, 0.6f, 0.8f, 0, 0, 0, 0, 0}, null);

        MostSimilarService.Suggestion first = null;
        for (int i = 0; i < 20; i++) {
            MostSimilarService.Suggestion next = service.findBestSuggestion().orElseThrow();
            if (first == null) {
                first = next;
            } else {
                assertEquals(first.name().name(), next.name().name(),
                        "parallel scoring must be deterministic: same name every run");
                assertEquals(first.candidate().id(), next.candidate().id(),
                        "parallel scoring must be deterministic: same face every run");
                assertEquals(first.nameSimilarity(), next.nameSimilarity(), 1e-12,
                        "parallel scoring must be deterministic: same similarity every run");
            }
        }
        assertEquals("Bob", first.name().name(),
                "Bob and Carol tie at 1.0; the lower name id wins");
        assertEquals(sharedBest, first.candidate().id());
        assertEquals(1.0, first.nameSimilarity(), 1e-9);
    }

    @Test
    void findBestSuggestion_lowerNameIdWinsOnExactTie() throws SQLException {
        // Alice and Bob have identical averages; the same unnamed face is
        // equally similar to both. The lower name id must win.
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgA2", xLike(), alice);
        addImageAndFace("imgB1", xLike(), bob);
        addImageAndFace("imgB2", xLike(), bob);
        long shared = addImageAndFace("imgU1", xLike(), null);

        MostSimilarService.Suggestion best = service.findBestSuggestion().orElseThrow();

        assertEquals("Alice", best.name().name(),
                "exact tie on similarity must keep the lower name id");
        assertEquals(shared, best.candidate().id());
        assertEquals(1.0, best.nameSimilarity(), 1e-9);
    }

    @Test
    void tagSelected_tagsEverySelectedFaceWithItsName() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgB1", yLike(), bob);
        long forAlice = addImageAndFace("imgU1", xLike(), null);
        long forBob = addImageAndFace("imgU2", yLike(), null);

        MostSimilarService.Suggestion alicePair = new MostSimilarService.Suggestion(
                nameDao.findById(alice).orElseThrow(),
                faceDao.findById(forAlice).orElseThrow(),
                faceDao.findById(forAlice).orElseThrow(), 1.0, 1.0);
        MostSimilarService.Suggestion bobPair = new MostSimilarService.Suggestion(
                nameDao.findById(bob).orElseThrow(),
                faceDao.findById(forBob).orElseThrow(),
                faceDao.findById(forBob).orElseThrow(), 1.0, 1.0);

        service.tagSelected(List.of(alicePair, bobPair));

        assertEquals(Long.valueOf(alice), faceDao.findById(forAlice).orElseThrow().nameId(),
                "every selected face must be tagged with its suggested name");
        assertEquals(Long.valueOf(bob), faceDao.findById(forBob).orElseThrow().nameId(),
                "every selected face must be tagged with its suggested name");
        assertTrue(service.findBestSuggestion().isEmpty(),
                "tagged faces must leave the unnamed pool");
    }

    @Test
    void rejectSelected_recordsEveryRejection() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgB1", yLike(), bob);
        long forAlice = addImageAndFace("imgU1", xLike(), null);
        long forBob = addImageAndFace("imgU2", yLike(), null);

        MostSimilarService.Suggestion alicePair = new MostSimilarService.Suggestion(
                nameDao.findById(alice).orElseThrow(),
                faceDao.findById(forAlice).orElseThrow(),
                faceDao.findById(forAlice).orElseThrow(), 1.0, 1.0);
        MostSimilarService.Suggestion bobPair = new MostSimilarService.Suggestion(
                nameDao.findById(bob).orElseThrow(),
                faceDao.findById(forBob).orElseThrow(),
                faceDao.findById(forBob).orElseThrow(), 1.0, 1.0);

        service.rejectSelected(List.of(alicePair, bobPair));

        assertTrue(notMatchDao.isNotMatch(forAlice, alice),
                "every selected pair must be recorded as a rejection");
        assertTrue(notMatchDao.isNotMatch(forBob, bob),
                "every selected pair must be recorded as a rejection");
        Optional<MostSimilarService.Suggestion> best = service.findBestSuggestion();
        best.ifPresent(suggestion -> {
            assertFalse(suggestion.candidate().id() == forAlice && suggestion.name().id() == alice,
                    "a rejected pair must not be offered again");
            assertFalse(suggestion.candidate().id() == forBob && suggestion.name().id() == bob,
                    "a rejected pair must not be offered again");
        });
    }

    @Test
    void skipAll_hidesPairsForThisSessionWithoutWritingToDatabase() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgB1", yLike(), bob);
        long forAlice = addImageAndFace("imgU1", xLike(), null);
        long forBob = addImageAndFace("imgU2", yLike(), null);

        MostSimilarService.Suggestion alicePair = new MostSimilarService.Suggestion(
                nameDao.findById(alice).orElseThrow(),
                faceDao.findById(forAlice).orElseThrow(),
                faceDao.findById(forAlice).orElseThrow(), 1.0, 1.0);
        MostSimilarService.Suggestion bobPair = new MostSimilarService.Suggestion(
                nameDao.findById(bob).orElseThrow(),
                faceDao.findById(forBob).orElseThrow(),
                faceDao.findById(forBob).orElseThrow(), 1.0, 1.0);

        service.skipAll(List.of(alicePair, bobPair));

        assertTrue(notMatchDao.findFaceIdsByNameId(alice).isEmpty(),
                "skipping must not write to the database");
        assertTrue(notMatchDao.findFaceIdsByNameId(bob).isEmpty(),
                "skipping must not write to the database");

        // The skipped pairs are hidden while the session lasts.
        MostSimilarService fresh = new MostSimilarService(
                new FaceToNameService(new FaceAiService(new FakeFaceAiEngine()), faceDao,
                        nameDao, notMatchDao, new ImageDao(db.getConnection()),
                        new VideoDao(db.getConnection()),
                        new ConfigStore(ConfigModel.defaults())),
                new FaceAiService(new FakeFaceAiEngine()), faceDao, nameDao, notMatchDao,
                db.getTransactionRunner());
        assertTrue(fresh.findBestSuggestion().isPresent(),
                "a fresh session must not know about this session's skips");
    }

    @Test
    void findBestSuggestion_earlyExitAtThreshold_stopsScanning() throws SQLException {
        configStore.updateAndGet(config -> config.withMinEarlyExitSimilarity(0.99));

        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        long forAlice = addImageAndFace("imgU1", xLike(), null);

        int callsBefore = engine.similarityCalls();
        Optional<MostSimilarService.Suggestion> best = service.findBestSuggestion();

        assertTrue(best.isPresent(), "a library with a qualifying pair must yield a pair");
        assertEquals("Alice", best.get().name().name(),
                "the only name must win");
        assertEquals(1.0, best.get().nameSimilarity(), 1e-9,
                "the candidate is identical to the average");
        // One name, one candidate: one similarity call for scoring, one for
        // the representative, one for the shown pair = 3 total.
        assertEquals(callsBefore + 3, engine.similarityCalls(),
                "early exit means only the winning name is scored");
    }

    @Test
    void findBestSuggestion_earlyExitThresholdOne_fullScan() throws SQLException {
        configStore.updateAndGet(config -> config.withMinEarlyExitSimilarity(1.0));

        float near = 0.9f;
        float side = (float) Math.sqrt(1.0 - near * near);
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgB1", yLike(), bob);
        // Neither candidate reaches similarity above 1.0, so no early exit.
        addImageAndFace("imgU1", new float[]{near, side, 0, 0, 0, 0, 0, 0}, null);
        addImageAndFace("imgU2", new float[]{0, 1, 0, 0, 0, 0, 0, 0}, null);

        Optional<MostSimilarService.Suggestion> best = service.findBestSuggestion();

        assertTrue(best.isPresent());
        assertEquals("Bob", best.get().name().name(),
                "Bob's candidate at 1.0 beats Alice's at 0.9");
        assertEquals(1.0, best.get().nameSimilarity(), 1e-9,
                "Bob's candidate is identical to his average");
    }

    @Test
    void getBacklogCount_countsUnnamedAndNamedFaces() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addImageAndFace("imgA1", xLike(), alice);   // tagged face
        addImageAndFace("imgU1", xLike(), null);    // unnamed face
        addImageAndFace("imgU2", yLike(), null);    // unnamed face

        MostSimilarService.BacklogCount backlog = service.getBacklogCount();

        assertEquals(2, backlog.unnamedFaces(),
                "two unnamed faces should be counted");
        assertEquals(1, backlog.namesWithFaces(),
                "only Alice has a tagged face, so one name with faces");
    }

    @Test
    void getBacklogCount_updatesAfterTagging() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        long candidate = addImageAndFace("imgU1", xLike(), null);

        MostSimilarService.BacklogCount before = service.getBacklogCount();
        assertEquals(1, before.unnamedFaces());
        assertEquals(1, before.namesWithFaces());

        service.markSamePerson(candidate, alice);

        MostSimilarService.BacklogCount after = service.getBacklogCount();
        assertEquals(0, after.unnamedFaces(),
                "tagged face leaves the unnamed pool");
        assertEquals(1, after.namesWithFaces(),
                "Alice still has a tagged face");
    }

    @Test
    void getBacklogCount_updatesAfterRejection() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        long candidate = addImageAndFace("imgU1", xLike(), null);

        service.markDifferentPerson(candidate, alice);

        MostSimilarService.BacklogCount backlog = service.getBacklogCount();
        assertEquals(1, backlog.unnamedFaces(),
                "rejected face is still unnamed (candidate for other names)");
        assertEquals(1, backlog.namesWithFaces());
    }
}
