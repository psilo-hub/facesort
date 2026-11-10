package free.svoss.facesort.service;

import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.FaceThumb;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.model.SimilarityResult;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Narrow service interface for the "Put a name to a face" and "Tag random
 * face" tabs.
 *
 * <p>Only exposes the operations the views need: clustering unnamed faces,
 * ranking faces by similarity, tagging faces with names, marking faces as
 * not-a-face, and resolving media file paths. Plus the media-file actions
 * used by the face context menus. Kept intentionally small so the views can
 * be tested with a minimal mock.</p>
 */
public interface NamingService extends MediaFileActions {

    /**
     * Returns clusters of unnamed faces, largest first.
     *
     * <p>Clusters are recomputed fresh on every call so newly tagged faces are
     * excluded the next time the flow is started.</p>
     *
     * @return the clusters, ordered by descending size
     * @throws SQLException if the underlying data cannot be read
     */
    List<ClusteringService.Cluster> getClusters() throws SQLException;

    /**
     * Returns all stored on-disk paths for the source image of a face.
     *
     * @param imageHash hash of the source image; must not be null
     * @return the stored paths, never {@code null}; possibly empty
     * @throws NullPointerException if {@code imageHash} is null
     * @throws SQLException         if the database operation fails
     */
    List<String> findImagePaths(String imageHash) throws SQLException;

    /**
     * Resolves the path to filter by for the media file behind a face. For a
     * video frame this is the full path of its source video (including the file
     * name), otherwise the folder of the image itself; when several paths are
     * stored the first one that still exists on disk is preferred.
     *
     * @param imageHash hash of the source image; must not be null
     * @return the path to filter by, or empty when no path is stored at all
     * @throws NullPointerException if {@code imageHash} is null
     * @throws SQLException         if the database operation fails
     */
    Optional<Path> resolveFilterPath(String imageHash) throws SQLException;

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
    long createOrFindName(String name) throws SQLException;

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
    Optional<NameRecord> findName(String name) throws SQLException;

    /**
     * Assigns the given name to the given face.
     *
     * @param faceId id of the face to tag
     * @param nameId id of the name to assign
     * @throws SQLException if the update fails
     */
    void tagFace(long faceId, long nameId) throws SQLException;

    /**
     * Records that the given face is not a face after all, taking it out of the
     * random sample and out of every name's candidate suggestions. The face row
     * is kept, so the decision survives a re-import of the image.
     *
     * @param faceId id of the face to dismiss
     * @throws SQLException if the update fails
     */
    void markNotAFace(long faceId) throws SQLException;

    /**
     * Returns the top {@code limit} unnamed faces most similar to the given
     * face, sorted by descending similarity. The face itself is excluded.
     *
     * @param faceId id of the reference (just-tagged) face
     * @param limit  maximum number of results; must be positive
     * @return matching faces sorted by descending similarity, never {@code null}
     * @throws IllegalArgumentException if {@code faceId} is unknown or {@code limit}
     *                                  is not positive
     * @throws SQLException             if the database operation fails
     */
    List<SimilarityResult> findSimilarUnnamed(long faceId, int limit) throws SQLException;

    /**
     * Ranks a fixed list of candidate faces by similarity to a reference face,
     * returning at most {@code limit} results in descending order. The
     * reference face itself is skipped. Used by the UI to preview the other
     * faces of the current cluster before the representative is tagged.
     *
     * @param reference  the reference face (e.g. the current cluster
     *                   representative)
     * @param candidates the candidate faces to rank; the reference is excluded
     * @param limit      maximum number of results; must be positive
     * @return the most similar candidates sorted by descending similarity,
     *         never {@code null}
     * @throws IllegalArgumentException if {@code limit} is not positive
     * @throws NullPointerException     if {@code reference} or {@code candidates}
     *                                  is null
     */
    List<SimilarityResult> rankSimilar(FaceRecord reference,
                                       List<FaceRecord> candidates,
                                       int limit);

    /**
     * Returns up to {@code limit} random unnamed faces.
     *
     * @param limit maximum number of faces; must not be negative
     * @return the random sample, never {@code null}; faces are unnamed
     * @throws IllegalArgumentException if {@code limit} is negative
     * @throws SQLException             if the database operation fails
     */
    List<FaceRecord> findRandomUnnamed(int limit) throws SQLException;

    /**
     * Returns up to {@code limit} random unnamed faces, optionally restricted
     * to images whose stored path starts with the given prefix, or (for video
     * frames) whose video file path starts with it. A {@code null} or blank
     * prefix disables the restriction.
     *
     * @param limit      maximum number of faces; must not be negative
     * @param pathPrefix path prefix the stored photo/video path must start with,
     *                   or {@code null}/{@code ""} for any faces
     * @return the random sample, never {@code null}; faces are unnamed
     * @throws IllegalArgumentException if {@code limit} is negative
     * @throws SQLException             if the database operation fails
     */
    List<FaceRecord> findRandomUnnamed(int limit, String pathPrefix)
            throws SQLException;

    /**
     * Returns up to {@code limit} random unnamed thumbnails, optionally
     * restricted to images whose stored path starts with the given prefix.
     *
     * @param limit      maximum number of faces; must not be negative
     * @param pathPrefix path prefix the stored photo/video path must start with,
     *                   or {@code null}/{@code ""} for any faces
     * @return the random sample, never {@code null}; faces are unnamed
     * @throws IllegalArgumentException if {@code limit} is negative
     * @throws SQLException             if the database operation fails
     */
    List<FaceThumb> findRandomUnnamedThumbs(int limit, String pathPrefix)
            throws SQLException;
}
