package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.model.SimilarityResult;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Implements the "Put a name to a face" workflow.
 *
 * <p>The flow (see IMPLEMENTATION_PLAN.md 6.4) is:
 * <ol>
 *   <li>Run {@link ClusteringService} to group unnamed faces into clusters
 *       (largest first).</li>
 *   <li>The UI presents one cluster representative at a time.</li>
 *   <li>When the user tags a representative with a name, the name is created
 *       or reused, the representative is assigned to it, and the remaining
 *       unnamed faces are re-sorted by similarity to the tagged face.</li>
 *   <li>The most similar faces are presented (descending) so the user can tag
 *       more faces with the same name, then the flow moves to the next
 *       cluster representative.</li>
 * </ol>
 *
 * <p>This class only orchestrates; it owns no state beyond its collaborators.
 * All heavy lifting (clustering, embeddings) lives in the injected services.</p>
 *
 * <p><b>Dependency note:</b> {@link ClusteringService} is a peer service built
 * against the contract used here: a {@code ClusteringService.Cluster} record
 * exposing {@code faces()} ({@code List<FaceRecord>}) and {@code representative()}
 * ({@code FaceRecord}), plus a {@code clusterUnnamed()} method returning
 * {@code List<Cluster>}.</p>
 */
public final class NamingService {

    private final ClusteringService clusteringService;
    private final FaceAiService faceAiService;
    private final FaceDao faceDao;
    private final NameDao nameDao;
    private final ConfigModel config;

    /**
     * Creates a naming service.
     *
     * @param clusteringService produces clusters of unnamed faces
     * @param faceAiService     embedding similarity calculations
     * @param faceDao           access to the faces table
     * @param nameDao           access to the names table
     * @param config            application settings; reserved for tuning the
     *                          flow (e.g. similarity thresholds)
     */
    public NamingService(ClusteringService clusteringService,
                         FaceAiService faceAiService,
                         FaceDao faceDao,
                         NameDao nameDao,
                         ConfigModel config) {
        this.clusteringService = Objects.requireNonNull(clusteringService, "clusteringService");
        this.faceAiService = Objects.requireNonNull(faceAiService, "faceAiService");
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
        this.nameDao = Objects.requireNonNull(nameDao, "nameDao");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Returns clusters of unnamed faces, largest first.
     *
     * <p>Clusters are recomputed fresh on every call so newly tagged faces are
     * excluded the next time the flow is started.</p>
     *
     * @return the clusters, ordered by descending size
     * @throws SQLException if the underlying data cannot be read
     */
    public List<ClusteringService.Cluster> getClusters() throws SQLException {
        return clusteringService.clusterUnnamed();
    }

    /**
     * Returns up to {@code limit} random unnamed faces, for review-and-tag
     * browsing.
     *
     * @param limit maximum number of faces; must not be negative
     * @return the random sample, never {@code null}; faces are unnamed
     * @throws IllegalArgumentException if {@code limit} is negative
     * @throws SQLException             if the database operation fails
     */
    public List<FaceRecord> findRandomUnnamed(int limit) throws SQLException {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        if (limit == 0) {
            return List.of();
        }
        return faceDao.findRandomUnnamed(limit);
    }

    /**
     * Returns the id of the name, creating it if it does not exist yet.
     *
     * <p>The input is trimmed before lookup so accidental leading/trailing
     * whitespace does not create duplicate names.</p>
     *
     * @param name the display name; must not be null or blank
     * @return the existing or newly created name id
     * @throws NullPointerException     if {@code name} is null
     * @throws IllegalArgumentException if {@code name} is blank
     * @throws SQLException             if the database operation fails
     */
    public long createOrFindName(String name) throws SQLException {
        String trimmed = Objects.requireNonNull(name, "name").trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Optional<NameRecord> existing = nameDao.findByName(trimmed);
        if (existing.isPresent()) {
            return existing.get().id();
        }
        return nameDao.insert(trimmed);
    }

    /**
     * Assigns the given name to the given face.
     *
     * @param faceId id of the face to tag
     * @param nameId id of the name to assign
     * @throws SQLException if the update fails
     */
    public void tagFace(long faceId, long nameId) throws SQLException {
        faceDao.assignName(faceId, nameId);
    }

    /**
     * Returns the top {@code limit} unnamed faces most similar to the given
     * face, sorted by descending similarity. The face itself is excluded.
     *
     * <p>Similarity scores are computed against the tagged face's stored
     * embedding; only faces without a name are considered, so already tagged
     * faces never reappear in the suggestions.</p>
     *
     * @param faceId id of the reference (just-tagged) face
     * @param limit  maximum number of results; must be positive
     * @return matching faces sorted by descending similarity, never {@code null}
     * @throws IllegalArgumentException if {@code faceId} is unknown or {@code limit}
     *                                  is not positive
     * @throws SQLException             if the database operation fails
     */
    public List<SimilarityResult> findSimilarUnnamed(long faceId, int limit) throws SQLException {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        FaceRecord reference = faceDao.findById(faceId)
                .orElseThrow(() -> new IllegalArgumentException("Face not found: " + faceId));

        List<SimilarityResult> results = new ArrayList<>();
        for (FaceRecord candidate : faceDao.findUnnamed()) {
            if (candidate.id() == faceId) {
                continue;
            }
            double similarity = faceAiService.calcSimilarity(
                    reference.embedding(), candidate.embedding());
            results.add(new SimilarityResult(candidate, similarity));
        }

        // SimilarityResult sorts by descending similarity.
        Collections.sort(results);

        if (results.size() <= limit) {
            return results;
        }
        return new ArrayList<>(results.subList(0, limit));
    }
}