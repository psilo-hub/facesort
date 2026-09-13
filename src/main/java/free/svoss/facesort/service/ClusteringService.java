package free.svoss.facesort.service;

import com.github.jelmerk.hnswlib.core.DistanceFunctions;
import com.github.jelmerk.hnswlib.core.Item;
import com.github.jelmerk.hnswlib.core.SearchResult;
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex;
import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.model.FaceRecord;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Groups unnamed faces into clusters using an approximate DBSCAN over an
 * HNSW index (IMPLEMENTATION_PLAN.md section 6.3).
 *
 * <p>The pipeline is:</p>
 * <ol>
 *   <li>Load all unnamed faces ({@code faces.name_id IS NULL}).</li>
 *   <li>Build an HNSW index over the face embeddings using the cosine
 *       distance function (distance = 1 - similarity).</li>
 *   <li>For every face, query k-NN and keep neighbours whose similarity is
 *       at or above {@code clusteringThreshold}.</li>
 *   <li>Flood-fill the resulting sparse graph to find connected components;
 *       components with fewer than 2 faces are discarded (minPts = 2).</li>
 *   <li>Sort clusters by size, largest first.</li>
 *   <li>Pick each cluster's representative: the member whose embedding is
 *       closest to the cluster's centroid embedding.</li>
 * </ol>
 *
 * <p>The clustering is recomputed from scratch on every call, so a name
 * assignment made elsewhere is automatically excluded from the next run
 * because tagged faces are no longer unnamed.</p>
 */
public class ClusteringService {

    /** A cluster requires at least two faces (DBSCAN minPts). */
    private static final int MIN_PTS = 2;

    /** Tolerance when comparing float distances against the threshold. */
    private static final double EPSILON = 1e-6;

    private final FaceAiService faceAiService;
    private final FaceDao faceDao;
    private final ConfigModel config;

    /**
     * Creates a clustering service.
     *
     * @param faceAiService provides centroid averaging and similarity scoring
     * @param faceDao       data access for the faces table
     * @param config        application settings (clustering threshold and HNSW
     *                      parameters; see {@link ConfigModel})
     */
    public ClusteringService(FaceAiService faceAiService, FaceDao faceDao, ConfigModel config) {
        this.faceAiService = faceAiService;
        this.faceDao = faceDao;
        this.config = config;
    }

    /** One cluster of unnamed faces plus its representative face. */
    public record Cluster(List<FaceRecord> faces, FaceRecord representative) {
    }

    /**
     * Runs clustering over all currently unnamed faces.
     *
     * @return clusters sorted by size (largest first); an empty list when there
     *         are fewer than two unnamed faces with usable embeddings
     * @throws SQLException if the face query fails
     */
    public List<Cluster> clusterUnnamed() throws SQLException {
        List<FaceRecord> faces = loadUnnamedFaces();
        if (faces.size() < MIN_PTS) {
            return List.of();
        }

        double threshold = config.getClusteringThreshold();
        HnswIndex<Long, float[], EmbeddingItem, Float> index = buildIndex(faces);
        List<Set<Integer>> adjacency = buildAdjacency(faces, index, threshold);
        List<List<Integer>> components = findConnectedComponents(adjacency);

        List<Cluster> clusters = new ArrayList<>();
        for (List<Integer> component : components) {
            List<FaceRecord> members = component.stream()
                    .map(faces::get)
                    .sorted(Comparator.comparingLong(FaceRecord::id))
                    .toList();
            clusters.add(new Cluster(members, pickRepresentative(members)));
        }
        clusters.sort(Comparator.comparingInt((Cluster c) -> c.faces().size()).reversed());
        return clusters;
    }

    /**
     * Loads all unnamed faces, keeping only those with a usable embedding.
     * Faces without an embedding (or with a dimension different from the
     * first face's) are skipped so the HNSW index stays dimension-consistent.
     */
    private List<FaceRecord> loadUnnamedFaces() throws SQLException {
        List<FaceRecord> faces = new ArrayList<>();
        for (FaceRecord face : faceDao.findUnnamed()) {
            float[] embedding = face.embedding();
            if (embedding != null && embedding.length > 0) {
                faces.add(face);
            }
        }
        if (faces.size() >= MIN_PTS) {
            int dimensions = faces.get(0).embedding().length;
            faces.removeIf(face -> face.embedding().length != dimensions);
        }
        return faces;
    }

    /**
     * Builds an HNSW index over the given faces, keyed by face id.
     */
    private HnswIndex<Long, float[], EmbeddingItem, Float> buildIndex(List<FaceRecord> faces) {
        int dimensions = faces.get(0).embedding().length;
        HnswIndex<Long, float[], EmbeddingItem, Float> index = HnswIndex
                .<float[], Float>newBuilder(dimensions, DistanceFunctions.FLOAT_COSINE_DISTANCE, faces.size())
                .withM(config.getHnswM())
                .withEfConstruction(config.getHnswEfConstruction())
                .withEf(config.getHnswEfSearch())
                .build();
        for (FaceRecord face : faces) {
            index.add(new EmbeddingItem(face.id(), face.embedding()));
        }
        return index;
    }

    /**
     * Builds the sparse neighbour graph: node i is linked to node j when the
     * cosine similarity of their embeddings is at or above the threshold.
     * Self-matches returned by the k-NN query are ignored.
     */
    private List<Set<Integer>> buildAdjacency(List<FaceRecord> faces,
                                              HnswIndex<Long, float[], EmbeddingItem, Float> index,
                                              double threshold) {
        int n = faces.size();
        int k = Math.max(1, Math.min(config.getKnnK(), n));
        List<Set<Integer>> adjacency = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacency.add(new HashSet<>());
        }

        for (int i = 0; i < n; i++) {
            float[] embedding = faces.get(i).embedding();
            List<SearchResult<EmbeddingItem, Float>> neighbours = index.findNearest(embedding, k);
            for (SearchResult<EmbeddingItem, Float> result : neighbours) {
                EmbeddingItem item = result.item();
                if (item.id() == faces.get(i).id()) {
                    continue; // the query point itself (distance 0)
                }
                double similarity = 1.0 - result.distance().doubleValue();
                if (similarity >= threshold - EPSILON) {
                    int j = indexOf(faces, item.id());
                    if (j >= 0 && j != i) {
                        adjacency.get(i).add(j);
                        adjacency.get(j).add(i);
                    }
                }
            }
        }
        return adjacency;
    }

    /**
     * Finds the list position of the face with the given id, or -1.
     * Faces must be unique in the table, so the first match is the match.
     */
    private int indexOf(List<FaceRecord> faces, long id) {
        for (int i = 0; i < faces.size(); i++) {
            if (faces.get(i).id() == id) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Runs a flood fill over the adjacency graph and returns all connected
     * components with at least {@link #MIN_PTS} members.
     */
    private List<List<Integer>> findConnectedComponents(List<Set<Integer>> adjacency) {
        int n = adjacency.size();
        boolean[] visited = new boolean[n];
        List<List<Integer>> components = new ArrayList<>();

        for (int start = 0; start < n; start++) {
            if (visited[start]) {
                continue;
            }
            List<Integer> component = new ArrayList<>();
            Deque<Integer> stack = new ArrayDeque<>();
            stack.push(start);
            visited[start] = true;
            while (!stack.isEmpty()) {
                int node = stack.pop();
                component.add(node);
                for (int neighbour : adjacency.get(node)) {
                    if (!visited[neighbour]) {
                        visited[neighbour] = true;
                        stack.push(neighbour);
                    }
                }
            }
            if (component.size() >= MIN_PTS) {
                components.add(component);
            }
        }
        return components;
    }

    /**
     * Picks the cluster representative: the member whose embedding is most
     * similar to the centroid of all member embeddings.
     */
    private FaceRecord pickRepresentative(List<FaceRecord> members) {
        List<float[]> embeddings = members.stream().map(FaceRecord::embedding).toList();
        float[] centroid = faceAiService.calcAverage(embeddings);

        FaceRecord best = members.get(0);
        double bestSimilarity = -1.0;
        for (FaceRecord member : members) {
            double similarity = faceAiService.calcSimilarity(centroid, member.embedding());
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                best = member;
            }
        }
        return best;
    }

    /** HNSW item wrapping a face's embedding, keyed by the face's database id. */
    private record EmbeddingItem(Long id, float[] vector) implements Item<Long, float[]> {

        @Override
        public int dimensions() {
            return vector.length;
        }
    }
}