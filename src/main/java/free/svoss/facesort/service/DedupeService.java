package free.svoss.facesort.service;

import free.svoss.facesort.model.FaceRecord;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Narrow service interface for the "Deduplicate" tab.
 *
 * <p>Only exposes the operations the tab needs: scanning name pairs, merging
 * names, recording that a pair is not a duplicate, skipping a pair for the
 * session, and resetting session state. Plus the media-file actions used by
 * the face context menus. Kept intentionally small so the view can be tested
 * with a minimal mock.</p>
 */
public interface DedupeService extends MediaFileActions {

    /**
     * A pair of names that may be duplicates, with their similarity and a
     * representative face for each name to display to the user.
     *
     * @param nameIdA    id of the first name
     * @param nameIdB    id of the second name
     * @param similarity similarity between the two average embeddings
     * @param nameA      display name of the first name
     * @param nameB      display name of the second name
     * @param repFaceA   face closest to the first name's average embedding
     * @param repFaceB   face closest to the second name's average embedding
     */
    record DupeCandidate(long nameIdA, long nameIdB, double similarity,
                         String nameA, String nameB,
                         FaceRecord repFaceA, FaceRecord repFaceB) {
    }

    /**
     * Returns the next most similar name pair not already marked as distinct
     * and not skipped this run.
     *
     * @return the next candidate pair, or empty when no comparable pairs are left
     * @throws SQLException if the database cannot be queried
     */
    Optional<DupeCandidate> nextPair() throws SQLException;

    /**
     * Merges two names: reassigns all faces of the eliminated name to the
     * surviving name and deletes the eliminated name row.
     *
     * @param survivorNameId   the name that survives the merge
     * @param eliminatedNameId the name to eliminate and delete
     * @throws SQLException if the database cannot be updated
     */
    void merge(long survivorNameId, long eliminatedNameId) throws SQLException;

    /**
     * Persistently marks two names as not duplicates.
     *
     * @param nameIdA id of the first name
     * @param nameIdB id of the second name
     * @throws SQLException if the database cannot be updated
     */
    void markNotDupes(long nameIdA, long nameIdB) throws SQLException;

    /**
     * Skips a pair for the remainder of this session.
     *
     * @param nameIdA id of the first name
     * @param nameIdB id of the second name
     */
    void skip(long nameIdA, long nameIdB);

    /**
     * Clears all session state, so a fresh ranking can begin.
     */
    void reset();
}
