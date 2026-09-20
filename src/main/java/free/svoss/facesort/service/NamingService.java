package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.model.SimilarityResult;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
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
    private final ImageDao imageDao;
    private final ConfigModel config;

    /**
     * Creates a naming service.
     *
     * @param clusteringService produces clusters of unnamed faces
     * @param faceAiService     embedding similarity calculations
     * @param faceDao           access to the faces table
     * @param nameDao           access to the names table
     * @param imageDao          access to the images and image_paths tables
     * @param config            application settings; reserved for tuning the
     *                          flow (e.g. similarity thresholds)
     */
    public NamingService(ClusteringService clusteringService,
                         FaceAiService faceAiService,
                         FaceDao faceDao,
                         NameDao nameDao,
                         ImageDao imageDao,
                         ConfigModel config) {
        this.clusteringService = Objects.requireNonNull(clusteringService, "clusteringService");
        this.faceAiService = Objects.requireNonNull(faceAiService, "faceAiService");
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
        this.nameDao = Objects.requireNonNull(nameDao, "nameDao");
        this.imageDao = Objects.requireNonNull(imageDao, "imageDao");
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
     * Returns all stored on-disk paths for the source image of a face.
     *
     * <p>An image may be reachable through several paths when the same file
     * exists at multiple locations, so the returned list can contain more than
     * one entry. It is empty when the image was never imported from disk or
     * its paths have been removed, in which case the UI shows a fallback
     * message instead.</p>
     *
     * @param imageHash hash of the source image; must not be null
     * @return the stored paths, never {@code null}; possibly empty
     * @throws NullPointerException if {@code imageHash} is null
     * @throws SQLException         if the database operation fails
     */
    public List<String> findImagePaths(String imageHash) throws SQLException {
        return imageDao.getPaths(Objects.requireNonNull(imageHash, "imageHash"));
    }

    /**
     * Tells whether the original file for the given image hash still exists on
     * disk.
     *
     * @param imageHash hash of the source image; must not be null
     * @return {@code true} if at least one stored path exists on disk
     * @throws NullPointerException if {@code imageHash} is null
     * @throws SQLException         if the database operation fails
     */
    public boolean isOriginalAvailable(String imageHash) throws SQLException {
        for (String path : findImagePaths(imageHash)) {
            if (java.nio.file.Files.exists(java.nio.file.Path.of(path))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens the original file of the source image for a face in the operating
     * system default viewer.
     *
     * <p>The first still-existing stored path for the image is used. Returns
     * {@code false} without side effects when no stored path exists on disk or
     * when the desktop platform does not support opening files.</p>
     *
     * @param imageHash hash of the source image; must not be null
     * @return {@code true} if the file was handed to the default viewer
     * @throws IOException  if the default viewer cannot open the file
     * @throws SQLException on database access failure
     */
    public boolean openOriginal(String imageHash) throws IOException, SQLException {
        Optional<Path> existing = ViewService.firstExistingPath(
                findImagePaths(Objects.requireNonNull(imageHash, "imageHash")));
        if (existing.isEmpty()) {
            return false;
        }
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            return false;
        }
        Desktop.getDesktop().open(existing.get().toFile());
        return true;
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
        return findRandomUnnamed(limit, null);
    }

    /**
     * Returns up to {@code limit} random unnamed faces, optionally restricted
     * to images that have at least one stored photo path starting with the
     * given prefix, or (for video frames) whose video file path starts with
     * it. A {@code null} or blank prefix disables the restriction.
     *
     * @param limit      maximum number of faces; must not be negative
     * @param pathPrefix path prefix the stored photo/video path must start with,
     *                   or {@code null}/{@code ""} for any faces
     * @return the random sample, never {@code null}; faces are unnamed
     * @throws IllegalArgumentException if {@code limit} is negative
     * @throws SQLException             if the database operation fails
     */
    public List<FaceRecord> findRandomUnnamed(int limit, String pathPrefix) throws SQLException {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        if (limit == 0) {
            return List.of();
        }
        return faceDao.findRandomUnnamed(limit, pathPrefix);
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
     * Returns the existing name record for the given display name, or empty
     * when the name has not been created yet. The lookup is trimmed and the
     * record carries the current face count.
     *
     * @param name the display name; must not be null or blank
     * @return the existing record, or empty if the name is new
     * @throws NullPointerException if {@code name} is null
     * @throws SQLException         if the database operation fails
     */
    public Optional<NameRecord> findName(String name) throws SQLException {
        return nameDao.findByName(Objects.requireNonNull(name, "name").trim());
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

    /**
     * Ranks a fixed list of candidate faces by similarity to a reference face,
     * returning at most {@code limit} results in descending order. The
     * reference face itself is skipped. Used by the UI to preview the other
     * faces of the current cluster before the representative is tagged.
     *
     * @param reference  the reference face (e.g. the current cluster representative)
     * @param candidates the candidate faces to rank; the reference is excluded
     * @param limit      maximum number of results; must be positive
     * @return the most similar candidates sorted by descending similarity,
     *         never {@code null}
     * @throws IllegalArgumentException if {@code limit} is not positive
     * @throws NullPointerException     if {@code reference} or {@code candidates}
     *                                  is null
     */
    public List<SimilarityResult> rankSimilar(FaceRecord reference,
                                              List<FaceRecord> candidates,
                                              int limit) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(candidates, "candidates");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }

        List<SimilarityResult> results = new ArrayList<>();
        for (FaceRecord candidate : candidates) {
            if (candidate.id() == reference.id()) {
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