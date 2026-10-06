package free.svoss.facesort.service;

import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex;
import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.config.ConfigStore;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        ConfigModel config = ConfigModel.defaults();
        config = config.withClusteringThreshold(0.5);
        config = config.withHnswM(16);
        config = config.withHnswEfConstruction(200);
        config = config.withHnswEfSearch(100);
        config = config.withKnnK(20);

        service = new ClusteringService(new FaceAiService(new FakeFaceAiEngine()), faceDao,
                new ConfigStore(config));
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
    void clusterUnnamed_videoFrameFaceClustersWithSimilarPhotoFace() throws SQLException {
        insertFace("imgPhoto", vectorX());
        VideoDao videoDao = new VideoDao(db.getConnection());
        ImageDao imageDao = new ImageDao(db.getConnection());
        imageDao.insert("frame1", 0, "{}", 1);
        imageDao.saveThumbnail("frame1", new byte[]{1, 2, 3});
        videoDao.insert("videoA", 0L, "{}", 10.0);
        videoDao.linkFrame("frame1", "videoA", 1000L);
        faceDao.insert(new FaceRecord(0, "frame1", 10, 10, 80, 80, 0.9,
                vectorX(), new byte[]{1}, null));

        List<ClusteringService.Cluster> clusters = service.clusterUnnamed();

        assertEquals(1, clusters.size(),
                "clustering is embedding-based and must not care about the source");
        assertEquals(2, clusters.get(0).faces().size());
    }

    @Test
    void clusterUnnamed_twoIdenticalFacesFormOneCluster() throws SQLException {
        insertFace("img1", vectorX());
        insertFace("img2", vectorX());

        List<ClusteringService.Cluster> clusters = service.clusterUnnamed();

        assertEquals(1, clusters.size());
        assertEquals(2, clusters.get(0).faces().size());
    }

    @Test
    void buildIndex_carriesThePositionOfEachFaceInTheList() {
        // Deliberately non-monotonic ids: an HNSW item's position is its index
        // in the face list, never anything derived from the id. That is what
        // lets the neighbour graph resolve a hit in constant time.
        List<FaceRecord> faces = List.of(
                detachedFace(70L, vectorX()),
                detachedFace(10L, vectorY()),
                detachedFace(40L, vectorX()));

        HnswIndex<Long, float[], ClusteringService.EmbeddingItem, Float> index = service.buildIndex(faces);

        Map<Integer, Long> idByPosition = new HashMap<>();
        for (ClusteringService.EmbeddingItem item : index.items()) {
            idByPosition.put(item.position(), item.id());
        }
        assertEquals(Map.of(0, 70L, 1, 10L, 2, 40L), idByPosition);
    }

    /** A face that only needs to exist in memory, never in the database. */
    private static FaceRecord detachedFace(long id, float[] embedding) {
        return new FaceRecord(id, "img" + id, 10, 10, 80, 80, 0.9, embedding, new byte[]{1}, null);
    }
}