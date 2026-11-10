package free.svoss.facesort.service;

import com.github.jelmerk.hnswlib.core.DistanceFunctions;
import com.github.jelmerk.hnswlib.core.Item;
import com.github.jelmerk.hnswlib.core.SearchResult;
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex;
import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.config.ConfigStore;
import free.svoss.facesort.model.FaceRecord;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HNSW index over the unnamed face pool for fast approximate nearest-neighbour
 * queries. Built lazily on first use and rebuilt whenever the pool's version
 * stamp changes (see {@link UnnamedFacePool}).
 *
 * <p>HNSW is approximate: query results are over-fetched by the caller and
 * re-scored exactly with {@link FaceAiService#calcSimilarity(float[], float[])}.
 * Faces removed from the pool (tagged, dismissed) are tracked as dead and
 * excluded from query results.</p>
 *
 * <p>All access is synchronized so the index can be queried from the parallel
 * scoring section of a scan while decisions update the pool on other threads.</p>
 */
final class UnnamedFaceHnswIndex {

    /**
     * Over-fetch factor: the HNSW query returns up to {@code poolSize / 4}
     * candidates per name (capped at 200), which are then re-scored exactly
     * and filtered against rejected, skipped and below-cutoff candidates.
     * This keeps recall high while avoiding a full pool scan per name.
     */
    private static final int MAX_K = 200;

    private final UnnamedFacePool pool;
    private final ConfigStore configStore;

    private HnswIndex<Long, float[], EmbeddingItem, Float> index;
    private long versionCount = -1;
    private long versionMaxId = -1;
    private final Set<Long> deadIds = ConcurrentHashMap.newKeySet();

    UnnamedFaceHnswIndex(UnnamedFacePool pool, ConfigStore configStore) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.configStore = Objects.requireNonNull(configStore, "configStore");
    }

    /**
     * Ensures the index is built from the current pool, rebuilding it when
     * the pool's version stamp changed since the last build.
     *
     * @throws SQLException on database error
     */
    synchronized void ensureBuilt() throws SQLException {
        pool.get(); // refresh the pool's cache so the version stamp reflects the DB
        long[] stamp = pool.versionStamp();
        if (index != null && stamp[0] == versionCount && stamp[1] == versionMaxId) {
            return; // already built for the current pool
        }
        buildIndex();
        versionCount = stamp[0];
        versionMaxId = stamp[1];
        deadIds.clear();
    }

    private void buildIndex() throws SQLException {
        List<FaceRecord> faces = pool.get();
        if (faces.isEmpty()) {
            index = null;
            return;
        }

        ConfigModel config = configStore.get();
        int dimensions = -1;
        for (FaceRecord face : faces) {
            if (face.embedding() != null && face.embedding().length > 0) {
                dimensions = face.embedding().length;
                break;
            }
        }
        if (dimensions < 0) {
            index = null;
            return;
        }

        HnswIndex<Long, float[], EmbeddingItem, Float> newIndex = HnswIndex
                .<float[], Float>newBuilder(dimensions, DistanceFunctions.FLOAT_COSINE_DISTANCE, faces.size())
                .withM(config.hnswM())
                .withEfConstruction(config.hnswEfConstruction())
                .withEf(config.hnswEfSearch())
                .build();
        for (FaceRecord face : faces) {
            float[] vector = face.embedding();
            if (vector != null && vector.length == dimensions) {
                newIndex.add(new EmbeddingItem(face.id(), vector));
            }
        }
        index = newIndex;
    }

    /**
     * Returns up to {@code k} face ids closest to the given embedding, ordered
     * by approximate distance (closest first). Dead faces (removed from the
     * pool but not yet evicted from the index) are excluded.
     *
     * @param vector the query embedding
     * @param k      maximum number of results; capped at {@link #MAX_K}
     * @return closest face ids, or empty when the index is not built or empty
     */
    synchronized List<Long> query(float[] vector, int k) {
        if (index == null || k <= 0) {
            return List.of();
        }
        int effectiveK = Math.min(k, MAX_K);
        List<SearchResult<EmbeddingItem, Float>> results = index.findNearest(vector, effectiveK);
        List<Long> ids = new ArrayList<>(results.size());
        for (SearchResult<EmbeddingItem, Float> r : results) {
            long id = r.item().id();
            if (!deadIds.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Marks a face as dead: it was removed from the pool but may still be in
     * the index until the next rebuild. Query results will skip it.
     *
     * @param faceId id of the face to mark dead
     */
    void markDead(long faceId) {
        deadIds.add(faceId);
    }

    /** HNSW item wrapping a face's embedding, keyed by the face's database id. */
    record EmbeddingItem(Long id, float[] vector) implements Item<Long, float[]> {
        @Override
        public int dimensions() {
            return vector.length;
        }
    }
}
