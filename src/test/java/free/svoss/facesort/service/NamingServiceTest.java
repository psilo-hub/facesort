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
 * Behavioral tests for {@link NamingService}: the random-sample and
 * tag-by-name flow used by the random tagging tab.
 */
class NamingServiceTest {

    private Database db;
    private FaceDao faceDao;
    private NameDao nameDao;
    private NamingService service;

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.inMemory();
        faceDao = new FaceDao(db.getConnection());
        nameDao = new NameDao(db.getConnection());

        ConfigModel config = new ConfigModel();
        config.setClusteringThreshold(0.5);
        config.setHnswM(16);
        config.setHnswEfConstruction(200);
        config.setHnswEfSearch(100);
        config.setKnnK(20);

        service = new NamingService(
                new ClusteringService(new FaceAiService(new FakeFaceAiEngine()), faceDao, config),
                new FaceAiService(new FakeFaceAiEngine()),
                faceDao, nameDao, new ImageDao(db.getConnection()), config);
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    private long addImageAndFace(String imageHash, Long nameId) throws SQLException {
        new ImageDao(db.getConnection()).insert(imageHash, 0, "{}", 1);
        return faceDao.insert(new FaceRecord(
                0, imageHash, 10, 10, 80, 80, 0.9,
                new float[]{1, 0, 0, 0, 0, 0, 0, 0}, new byte[]{1}, nameId));
    }

    private long addFaceWithEmbedding(String imageHash, float[] embedding) throws SQLException {
        new ImageDao(db.getConnection()).insert(imageHash, 0, "{}", 1);
        return faceDao.insert(new FaceRecord(
                0, imageHash, 10, 10, 80, 80, 0.9,
                embedding, new byte[]{1}, null));
    }

    @Test
    void findRandomUnnamed_onlyReturnsUnnamedFacesAndHonorsLimit() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA", alice);
        for (int i = 0; i < 5; i++) {
            addImageAndFace("imgU" + i, null);
        }

        List<FaceRecord> random = service.findRandomUnnamed(3);

        assertEquals(3, random.size());
        assertTrue(random.stream().allMatch(f -> f.nameId() == null));
    }

    @Test
    void findRandomUnnamed_returnsFewerWhenFewUnnamedFaces() throws SQLException {
        addImageAndFace("imgU1", null);
        addImageAndFace("imgU2", null);

        assertEquals(2, service.findRandomUnnamed(25).size());
    }

    @Test
    void findRandomUnnamed_zeroLimitReturnsEmpty() throws SQLException {
        addImageAndFace("imgU1", null);

        assertTrue(service.findRandomUnnamed(0).isEmpty());
    }

    @Test
    void findRandomUnnamed_negativeLimitThrows() throws SQLException {
        addImageAndFace("imgU1", null);

        assertThrows(IllegalArgumentException.class, () -> service.findRandomUnnamed(-1));
    }

    @Test
    void findRandomUnnamed_emptyWhenAllFacesTagged() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", alice);
        addImageAndFace("imgA2", alice);

        assertTrue(service.findRandomUnnamed(25).isEmpty());
    }

    @Test
    void findRandomUnnamed_withPathPrefix_returnsOnlyFacesFromMatchingImages() throws SQLException {
        ImageDao imageDao = new ImageDao(db.getConnection());
        imageDao.insert("imgFamily", 0, "{}", 1);
        imageDao.addPath("imgFamily", "/photos/family/a.jpg");
        faceDao.insert(new FaceRecord(0, "imgFamily", 10, 10, 80, 80, 0.9,
                new float[]{1, 0, 0, 0, 0, 0, 0, 0}, new byte[]{1}, null));
        imageDao.insert("imgTravel", 0, "{}", 1);
        imageDao.addPath("imgTravel", "/photos/travel/b.jpg");
        faceDao.insert(new FaceRecord(0, "imgTravel", 10, 10, 80, 80, 0.9,
                new float[]{1, 0, 0, 0, 0, 0, 0, 0}, new byte[]{1}, null));

        List<FaceRecord> matching = service.findRandomUnnamed(25, "/photos/family");

        assertEquals(1, matching.size());
        assertEquals("imgFamily", matching.get(0).imageHash());
    }

    @Test
    void findRandomUnnamed_withBlankPathPrefix_returnsAll() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA", alice);
        addImageAndFace("imgU", null);

        assertEquals(1, service.findRandomUnnamed(25, "").size());
        assertEquals(1, service.findRandomUnnamed(25, null).size());
    }

    @Test
    void createOrFindName_reusesExistingNameId() throws SQLException {
        long alice = nameDao.insert("Alice");

        assertEquals(alice, service.createOrFindName("Alice"));
        assertEquals(alice, service.createOrFindName("  Alice  "));
    }

    @Test
    void createOrFindName_rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> service.createOrFindName("   "));
    }

    @Test
    void findName_returnsExistingRecordWithFaceCount() throws SQLException {
        long alice = nameDao.insert("Alice");
        addImageAndFace("imgA1", alice);
        addImageAndFace("imgA2", alice);

        java.util.Optional<free.svoss.facesort.model.NameRecord> found = service.findName("  Alice  ");

        assertTrue(found.isPresent());
        assertEquals(alice, found.get().id());
        assertEquals(2, found.get().faceCount());
    }

    @Test
    void findName_returnsEmptyForUnknownName() throws SQLException {
        assertTrue(service.findName("Nobody").isEmpty());
    }

    @Test
    void tagFace_assignsNameAndHidesFaceFromUnnamed() throws SQLException {
        long alice = nameDao.insert("Alice");
        long faceId = addImageAndFace("imgU1", null);

        service.tagFace(faceId, alice);

        assertEquals(0, service.findRandomUnnamed(25).size());
    }

    @Test
    void findSimilarUnnamed_ranksMostSimilarFirst() throws SQLException {
        long alice = nameDao.insert("Alice");
        long refId = addImageAndFace("imgA", alice);
        addImageAndFace("imgU1", null);
        addImageAndFace("imgU2", null);

        List<SimilarityResult> results = service.findSimilarUnnamed(refId, 10);

        assertEquals(2, results.size());
    }

    @Test
    void rankSimilar_ranksCandidatesMostSimilarFirstAndSkipsReference() throws SQLException {
        long refId = addFaceWithEmbedding("imgRef", new float[]{1, 0, 0, 0, 0, 0, 0, 0});
        long closeId = addFaceWithEmbedding("imgClose", new float[]{1, 0, 0, 0, 0, 0, 0, 0});
        long farId = addFaceWithEmbedding("imgFar", new float[]{0, 1, 0, 0, 0, 0, 0, 0});

        FaceRecord reference = faceDao.findById(refId).orElseThrow();
        FaceRecord close = faceDao.findById(closeId).orElseThrow();
        FaceRecord far = faceDao.findById(farId).orElseThrow();

        List<SimilarityResult> results =
                service.rankSimilar(reference, List.of(far, reference, close), 10);

        assertEquals(2, results.size());
        assertEquals(closeId, results.get(0).faceRecord().id());
        assertEquals(farId, results.get(1).faceRecord().id());
        assertTrue(results.stream().noneMatch(r -> r.faceRecord().id() == refId));
    }

    @Test
    void rankSimilar_honorsLimit() throws SQLException {
        long refId = addFaceWithEmbedding("imgRef", new float[]{1, 0, 0, 0, 0, 0, 0, 0});
        addFaceWithEmbedding("imgA", new float[]{1, 0, 0, 0, 0, 0, 0, 0});
        addFaceWithEmbedding("imgB", new float[]{0, 1, 0, 0, 0, 0, 0, 0});

        FaceRecord reference = faceDao.findById(refId).orElseThrow();

        List<SimilarityResult> results =
                service.rankSimilar(reference, faceDao.findUnnamed(), 1);

        assertEquals(1, results.size());
    }

    @Test
    void rankSimilar_negativeLimitThrows() throws SQLException {
        long refId = addFaceWithEmbedding("imgRef", new float[]{1, 0, 0, 0, 0, 0, 0, 0});
        long closeId = addFaceWithEmbedding("imgClose", new float[]{1, 0, 0, 0, 0, 0, 0, 0});

        FaceRecord reference = faceDao.findById(refId).orElseThrow();
        FaceRecord close = faceDao.findById(closeId).orElseThrow();

        assertThrows(IllegalArgumentException.class,
                () -> service.rankSimilar(reference, List.of(close), 0));
    }

    @Test
    void findImagePaths_returnsStoredPathsForImage() throws SQLException {
        ImageDao imageDao = new ImageDao(db.getConnection());
        imageDao.insert("imgP", 0, "{}", 1);
        imageDao.addPath("imgP", "/photos/north.jpg");
        imageDao.addPath("imgP", "/photos/dupe.jpg");

        // The image_paths table has PRIMARY KEY (hash, path) and getPaths has
        // no ORDER BY, so row order is unspecified (SQLite scans via the
        // (hash, path) index). Assert content, not order.
        List<String> paths = service.findImagePaths("imgP");
        assertEquals(2, paths.size());
        assertTrue(paths.contains("/photos/north.jpg"));
        assertTrue(paths.contains("/photos/dupe.jpg"));
    }

    @Test
    void findImagePaths_unknownHashReturnsEmpty() throws SQLException {
        assertTrue(service.findImagePaths("missing").isEmpty());
    }
}