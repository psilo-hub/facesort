package free.svoss.facesort.service;

import free.svoss.facesort.db.DataRemovalDao;
import free.svoss.facesort.db.TransactionRunner;

import java.sql.SQLException;
import java.util.Objects;

/**
 * Removes imported images and videos from the database whose stored file path
 * starts with a user-chosen prefix, as one atomic unit.
 *
 * <p>Before anything is deleted the UI asks {@link #estimate} for the affected
 * row counts (images, videos, thumbnails and face sub-images) and shows them to
 * the user; only after an explicit confirmation is {@link #remove} called, which
 * recomputes the same counts inside the same transaction and deletes exactly what
 * they describe. Photos whose path matches the prefix and videos whose path
 * matches the prefix vanish together with the frames sampled from them; a video
 * frame that is also a real photo is kept unless its own path matches. All the
 * side effects (thumbnails, face sub-images, frame links) cascade through the
 * database's foreign keys.</p>
 *
 * <p>A blank prefix matches nothing, so {@link #estimate} and {@link #remove}
 * are intentionally no-ops for a blank or {@code null} prefix.</p>
 */
public class DataRemovalService {

    private final DataRemovalDao dao;
    private final TransactionRunner transactions;

    /**
     * @param dao          DAO for the cross-table prefix removal queries
     * @param transactions runner for the atomic estimate/remove units
     */
    public DataRemovalService(DataRemovalDao dao, TransactionRunner transactions) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /**
     * Counts how many rows a prefix-based removal would delete, without
     * changing anything.
     *
     * @param prefix the photo/video path prefix (trimmed; blank matches nothing)
     * @return the affected-row counts
     * @throws SQLException on database access failure
     */
    public Estimate estimate(String prefix) throws SQLException {
        return transactions.inTransaction(() -> survey(trim(prefix)));
    }

    /**
     * Deletes every image and video whose stored path starts with the prefix,
     * together with their thumbnails, face sub-images and frame links.
     *
     * @param prefix the photo/video path prefix (trimmed; blank matches nothing)
     * @return the number of rows that were deleted
     * @throws SQLException on database access failure
     */
    public Removal remove(String prefix) throws SQLException {
        String trimmed = trim(prefix);
        return transactions.inTransaction(() -> {
            Estimate estimate = survey(trimmed);
            if (!estimate.hasMatches()) {
                return estimate.toRemoval();
            }
            // Images first: deleting them cascades their video_frames links, so
            // the orphan frames of a matched video are still matched when the
            // video deletion runs afterwards.
            dao.deleteAffectedImages(trimmed);
            dao.deleteAffectedVideos(trimmed);
            return estimate.toRemoval();
        });
    }

    private static String trim(String prefix) {
        return prefix == null ? "" : prefix.trim();
    }

    private Estimate survey(String trimmed) throws SQLException {
        if (trimmed.isEmpty()) {
            return new Estimate(0, 0, 0, 0);
        }
        return new Estimate(
                dao.countAffectedImages(trimmed),
                dao.countAffectedVideos(trimmed),
                dao.countAffectedThumbnails(trimmed),
                dao.countAffectedFaces(trimmed));
    }

    /**
     * Affected-row counts for one path prefix.
     *
     * @param images        images that would be removed (photos plus orphan frames of matched videos)
     * @param videos        videos that would be removed
     * @param thumbnails    thumbnails that would be removed
     * @param faceSubImages face sub-images that would be removed
     */
    public record Estimate(int images, int videos, int thumbnails, int faceSubImages) {

        /**
         * @return {@code true} when at least one image or video matches the prefix
         */
        public boolean hasMatches() {
            return images + videos > 0;
        }

        Removal toRemoval() {
            return new Removal(images, videos, thumbnails, faceSubImages);
        }
    }

    /**
     * Counts of an executed removal.
     *
     * @param images        images deleted
     * @param videos        videos deleted
     * @param thumbnails    thumbnails deleted
     * @param faceSubImages face sub-images deleted
     */
    public record Removal(int images, int videos, int thumbnails, int faceSubImages) {
    }
}