package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for {@link ClusteringService} using synthetic embeddings
 * and the real HNSW-based clustering pipeline over an in-memory database.
 */
class ClusteringServiceTest {

    private Database db;
    private FaceDao faceDao;
    private ClusteringService service;

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.inMemory();
        faceDao = new FaceDao(db.getConnection());

        ConfigModel config = new ConfigModel();
        config.setClusteringThreshold(0.5);
        config.setHnswM(16);
        config.setHnswEfConstruction(200);
        config.setHnswEfSearch(100);
        config.setKnnK(20);

        service = new ClusteringService(new FaceAiService(new FakeFaceAiEngine()), faceDao, config);
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    /** Inserts an image row (FK) plus an unnamed face with the given embedding. */
    private long insertFace(String imageHash, float[] embedding) throws SQLException {
        new ImageDao(db.getConnection()).insert(imageHash, 0, "{}", 1);
        return faceDao.insert(new FaceRecord(
                0, imageHash, 10, 10, 80, 80, 0.9, embedding, new byte[]{1}, null));
    }

    private static float[] vectorX() {
        return new float[]{1, 0, 0, 0, 0, 0, 0, 0};
    }

    private static float[] vectorY() {
        return new float[]{0, 1, 0, 0, 0, 0, 0, 0};
    }

    @Test
    void clusterUnnamed_separatesTwoDistinctGroups() throws SQLException {
        for (int i = 0; i < 3; i++) {
            insertFace("imgA-" + i, vectorX());
        }
        for (int i = 0; i < 3; i++) {
            insertFace("imgB-" + i, vectorY());
        }

        List<ClusteringService.Cluster> clusters = service.clusterUnnamed();

        assertEquals(2, clusters.size());
        for (ClusteringService.Cluster cluster : clusters) {
            // each cluster must contain exactly the members of one group
            assertEquals(3, cluster.faces().size());
            float[] firstEmbedding = cluster.faces().get(0).embedding();
            for (FaceRecord face : cluster.faces()) {
                assertArrayEquals(firstEmbedding, face.embedding(), 1e-6f);
            }
            assertTrue(cluster.faces().contains(cluster.representative()),
                    "representative must be a cluster member");
        }
    }

    @Test
    void clusterUnnamed_singleFaceYieldsNoClusters() throws SQLException {
        insertFace("img1", vectorX());

        assertTrue(service.clusterUnnamed().isEmpty());
    }

    @Test
    void clusterUnnamed_twoIdenticalFacesFormOneCluster() throws SQLException {
        insertFace("img1", vectorX());
        insertFace("img2", vectorX());

        List<ClusteringService.Cluster> clusters = service.clusterUnnamed();

        assertEquals(1, clusters.size());
        assertEquals(2, clusters.get(0).faces().size());
    }
}