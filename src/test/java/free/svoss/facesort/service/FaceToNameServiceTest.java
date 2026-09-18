package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.SimilarityResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for {@link FaceToNameService}: ranking unnamed faces by
 * similarity to a name's average embedding, and batch tagging.
 */
class FaceToNameServiceTest {

    private Database db;
    private FaceDao faceDao;
    private NameDao nameDao;
    private ImageDao imageDao;
    private FaceToNameService service;
    private ConfigModel config;

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.inMemory();
        faceDao = new FaceDao(db.getConnection());
        nameDao = new NameDao(db.getConnection());
        imageDao = new ImageDao(db.getConnection());
        config = new ConfigModel();
        config.setMinNameSimilarity(0.0); // no cutoff by default in these tests
        service = new FaceToNameService(new FaceAiService(new FakeFaceAiEngine()), faceDao, nameDao, imageDao, config);
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
    void findUnnamedForName_ranksMostSimilarUnnamedFaceFirst() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgA2", xLike(), alice);
        long close = addImageAndFace("imgU1", new float[]{0.9f, 0.1f, 0, 0, 0, 0, 0, 0}, null);
        addImageAndFace("imgU2", yLike(), null);

        List<SimilarityResult> results = service.findUnnamedForName(alice, 10);

        assertEquals(2, results.size());
        assertEquals(close, results.get(0).faceRecord().id());
        assertTrue(results.get(0).similarity() >= results.get(1).similarity(),
                "results must be sorted by descending similarity");
    }

    @Test
    void findUnnamedForName_respectsLimit() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgU1", new float[]{0.9f, 0.1f, 0, 0, 0, 0, 0, 0}, null);
        addImageAndFace("imgU2", yLike(), null);

        List<SimilarityResult> limited = service.findUnnamedForName(alice, 1);

        assertEquals(1, limited.size());
    }

    @Test
    void findUnnamedForName_filtersBelowCutoff() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgA2", xLike(), alice);
        long close = addImageAndFace("imgU1", new float[]{0.9f, 0.1f, 0, 0, 0, 0, 0, 0}, null);
        addImageAndFace("imgU2", yLike(), null);

        config.setMinNameSimilarity(0.75);
        List<SimilarityResult> results = service.findUnnamedForName(alice, 10);

        assertEquals(1, results.size());
        assertEquals(close, results.get(0).faceRecord().id());
        assertTrue(results.get(0).similarity() >= 0.75,
                "result must meet the similarity cutoff");
    }

    @Test
    void findUnnamedForName_emptyWhenNameHasNoFaces() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgU1", xLike(), null);

        assertTrue(service.findUnnamedForName(alice, 10).isEmpty());
    }

    @Test
    void findUnnamedForName_nonPositiveLimitReturnsEmpty() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgU1", xLike(), null);

        assertTrue(service.findUnnamedForName(alice, 0).isEmpty());
        assertTrue(service.findUnnamedForName(alice, -1).isEmpty());
    }

    @Test
    void findUnnamedForName_excludesFacesCloserToAnotherName() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgA2", xLike(), alice);
        addImageAndFace("imgB1", yLike(), bob);
        addImageAndFace("imgB2", yLike(), bob);
        long closeToAlice = addImageAndFace(
                "imgU1", new float[]{0.9f, 0.1f, 0, 0, 0, 0, 0, 0}, null);
        addImageAndFace("imgU2", new float[]{0.1f, 0.9f, 0, 0, 0, 0, 0, 0}, null);

        List<SimilarityResult> results = service.findUnnamedForName(alice, 10, true);

        assertEquals(1, results.size());
        assertEquals(closeToAlice, results.get(0).faceRecord().id());
    }

    @Test
    void findUnnamedForName_exclusionDoesNothingWhenOnlyOneNameExists() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgA2", xLike(), alice);
        long close = addImageAndFace("imgU1", new float[]{0.9f, 0.1f, 0, 0, 0, 0, 0, 0}, null);

        List<SimilarityResult> results = service.findUnnamedForName(alice, 10, true);

        assertEquals(1, results.size());
        assertEquals(close, results.get(0).faceRecord().id());
    }

    @Test
    void findUnnamedForName_exclusionKeepsEquallySimilarFaces() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgA2", xLike(), alice);
        addImageAndFace("imgB1", yLike(), bob);
        addImageAndFace("imgB2", yLike(), bob);
        long closeToBoth = addImageAndFace("imgU1", new float[]{1, 1, 0, 0, 0, 0, 0, 0}, null);

        List<SimilarityResult> results = service.findUnnamedForName(alice, 10, true);

        assertEquals(1, results.size());
        assertEquals(closeToBoth, results.get(0).faceRecord().id());
    }

    @Test
    void findUnnamedForName_withPathPrefix_onlyOffersCandidatesFromMatchingImages() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgA2", xLike(), alice);
        addImageAndFace("imgU1", new float[]{0.9f, 0.1f, 0, 0, 0, 0, 0, 0}, null);
        imageDao.addPath("imgA1", "/photos/family/winter.jpg");
        imageDao.addPath("imgA2", "/photos/family/winter.jpg");
        imageDao.addPath("imgU1", "/photos/family/summer.jpg");

        List<SimilarityResult> results = service.findUnnamedForName(alice, 10, false, "/photos");

        assertEquals(1, results.size(), "all images share the /photos prefix");
    }

    @Test
    void findUnnamedForName_withPathPrefix_filtersOutNonMatchingImages() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgA2", xLike(), alice);
        long matching = addImageAndFace("imgU1", new float[]{0.9f, 0.1f, 0, 0, 0, 0, 0, 0}, null);
        addImageAndFace("imgU2", new float[]{0.9f, 0.2f, 0, 0, 0, 0, 0, 0}, null);
        imageDao.addPath("imgA1", "/photos/family/a.jpg");
        imageDao.addPath("imgA2", "/photos/family/b.jpg");
        imageDao.addPath("imgU1", "/photos/family/c.jpg");
        imageDao.addPath("imgU2", "/other/location/d.jpg");

        List<SimilarityResult> results = service.findUnnamedForName(alice, 10, false, "/photos/family");

        assertEquals(1, results.size());
        assertEquals(matching, results.get(0).faceRecord().id());
    }

    @Test
    void findUnnamedForName_withNonMatchingPathPrefix_returnsEmpty() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgA2", xLike(), alice);
        addImageAndFace("imgU1", new float[]{0.9f, 0.1f, 0, 0, 0, 0, 0, 0}, null);
        imageDao.addPath("imgA1", "/photos/a.jpg");
        imageDao.addPath("imgA2", "/photos/b.jpg");

        assertTrue(service.findUnnamedForName(alice, 10, false, "/missing").isEmpty());
    }

    @Test
    void findMostSimilarNamed_ranksTaggedFacesBySimilarityToAverage() throws SQLException {
        long alice = nameDao.insert("Alice");
        long best = addImageAndFace("imgA1", xLike(), alice);
        long worst = addImageAndFace("imgA2", yLike(), alice);
        addImageAndFace("imgA3", xLike(), alice);

        List<SimilarityResult> results = service.findMostSimilarNamed(alice, 10);

        assertEquals(3, results.size());
        assertTrue(results.stream().noneMatch(r -> r.faceRecord().nameId() == null));
        assertTrue(results.stream().allMatch(r -> r.faceRecord().nameId() == alice));
        assertTrue(results.stream().allMatch(r -> r.similarity() >= 0));
        assertEquals(best, results.get(0).faceRecord().id());
        assertEquals(worst, results.get(2).faceRecord().id());
        assertTrue(results.get(0).similarity() >= results.get(1).similarity()
                        && results.get(1).similarity() >= results.get(2).similarity(),
                "results must be sorted by descending similarity");
    }

    @Test
    void findMostSimilarNamed_respectsLimit() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgA2", xLike(), alice);
        addImageAndFace("imgA3", yLike(), alice);

        List<SimilarityResult> limited = service.findMostSimilarNamed(alice, 2);

        assertEquals(2, limited.size());
    }

    @Test
    void findMostSimilarNamed_emptyWhenNameHasNoFaces() throws SQLException {
        long alice = nameDao.insert("Alice");

        assertTrue(service.findMostSimilarNamed(alice, 10).isEmpty());
    }

    @Test
    void findMostSimilarNamed_nonPositiveLimitReturnsEmpty() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);

        assertTrue(service.findMostSimilarNamed(alice, 0).isEmpty());
        assertTrue(service.findMostSimilarNamed(alice, -1).isEmpty());
    }

    @Test
    void tagFaces_assignsNameToMultipleFaces() throws SQLException {
        long alice = nameDao.insert("Alice");
        long f1 = addImageAndFace("imgU1", xLike(), null);
        long f2 = addImageAndFace("imgU2", xLike(), null);

        service.tagFaces(List.of(f1, f2), alice);

        assertEquals(2, faceDao.findByNameId(alice).size());
        assertTrue(faceDao.findUnnamed().isEmpty());
    }

    @Test
    void getAllNames_returnsNamesFromDatabase() throws SQLException {
        nameDao.insert("Bob");
        nameDao.insert("Alice");

        List<free.svoss.facesort.model.NameRecord> names = service.getAllNames();

        assertEquals(2, names.size());
        assertEquals("Alice", names.get(0).name());
        assertEquals("Bob", names.get(1).name());
    }

    @Test
    void renameName_updatesNameAndKeepsFaceAssignments() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", xLike(), alice);
        addImageAndFace("imgA2", xLike(), alice);

        free.svoss.facesort.model.NameRecord renamed = service.renameName(alice, "Alicia");

        assertEquals("Alicia", renamed.name());
        assertEquals(alice, renamed.id());
        assertEquals(2, renamed.faceCount());
        assertEquals(2, faceDao.findByNameId(alice).size(),
                "faces must stay assigned to the renamed name id");
        assertTrue(nameDao.findByName("Alice").isEmpty(), "old name must not exist");
    }

    @Test
    void renameName_trimsWhitespace() throws SQLException {
        long alice = nameDao.insert("Alice");

        service.renameName(alice, "  Alicia  ");

        assertEquals("Alicia", nameDao.findById(alice).orElseThrow().name());
    }

    @Test
    void renameName_blankNameThrows() throws SQLException {
        long alice = nameDao.insert("Alice");

        assertThrows(IllegalArgumentException.class, () -> service.renameName(alice, "   "));
        assertThrows(NullPointerException.class, () -> service.renameName(alice, null));
    }

    @Test
    void renameName_unknownNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.renameName(999L, "Nobody"));
    }

    @Test
    void renameName_existingNameThrows() throws SQLException {
        long alice = nameDao.insert("Alice");
        nameDao.insert("Bob");

        assertThrows(IllegalArgumentException.class, () -> service.renameName(alice, "Bob"),
                "a name may not be renamed to another name's label");
    }

    @Test
    void renameName_sameNameIsNoOp() throws SQLException {
        long alice = nameDao.insert("Alice");

        free.svoss.facesort.model.NameRecord renamed = service.renameName(alice, "  Alice  ");

        assertEquals("Alice", renamed.name());
        assertEquals(1, nameDao.count());
    }
}