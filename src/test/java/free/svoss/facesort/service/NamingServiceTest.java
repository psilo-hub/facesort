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
                faceDao, nameDao, config);
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
}