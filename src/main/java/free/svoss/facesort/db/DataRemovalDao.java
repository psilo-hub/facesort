package free.svoss.facesort.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Data access object for permanently removing imported images and videos whose
 * stored file path starts with a given prefix.
 *
 * <p>The removal covers photos whose stored path starts with the prefix plus the
 * frames sampled from videos whose stored path starts with the prefix — a video
 * frame that is also a real photo (it has a stored path of its own) is kept
 * unless its own path matches. Deleting an image cascades to its paths,
 * thumbnail and faces, and deleting a video cascades to its paths and frame
 * links, so the counts derived here are exactly the rows that disappear.</p>
 *
 * <p>The prefix is matched as the lexicographic range {@link PathPrefix}
 * builds, so SQLite can serve it from the index on the path column instead of
 * evaluating the comparison on every stored path.</p>
 */
public class DataRemovalDao {

    private final Connection conn;

    public DataRemovalDao(Connection conn) {
        this.conn = conn;
    }

    /**
     * Returns the statement listing the image hashes a prefix-based removal
     * deletes: photos whose stored path starts with the prefix, plus the frames
     * sampled from matching videos that are not also photos. The statement's
     * placeholders are the two path ranges, bound by
     * {@link #bindAffectedImageHashes}.
     *
     * @param filter the path prefix to match
     * @return the SQL selecting the affected image hashes
     */
    static String affectedImageHashesSql(PathPrefix filter) {
        return "SELECT DISTINCT ip.hash"
                + " FROM image_paths ip"
                + " WHERE " + filter.condition("ip.path")
                + " UNION"
                + " SELECT DISTINCT vf.frame_hash"
                + " FROM video_frames vf"
                + " JOIN video_paths vp ON vp.hash = vf.video_hash"
                + " WHERE " + filter.condition("vp.path")
                + " AND NOT EXISTS (SELECT 1 FROM image_paths op WHERE op.hash = vf.frame_hash)";
    }

    /**
     * Counts the images that would be removed for the given path prefix.
     *
     * @param prefix the photo/video path prefix; must not be blank
     * @return the number of affected images
     * @throws SQLException on database error
     */
    public int countAffectedImages(String prefix) throws SQLException {
        PathPrefix filter = PathPrefix.of(prefix);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM (" + affectedImageHashesSql(filter) + ")")) {
            bindAffectedImageHashes(ps, filter);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Counts the videos that would be removed for the given path prefix.
     *
     * @param prefix the video path prefix; must not be blank
     * @return the number of affected videos
     * @throws SQLException on database error
     */
    public int countAffectedVideos(String prefix) throws SQLException {
        PathPrefix filter = PathPrefix.of(prefix);
        try (PreparedStatement ps = conn.prepareStatement(countAffectedVideosSql(filter))) {
            filter.bind(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Counts the thumbnails that would be removed for the given path prefix.
     *
     * @param prefix the photo/video path prefix; must not be blank
     * @return the number of thumbnails stored on affected images
     * @throws SQLException on database error
     */
    public int countAffectedThumbnails(String prefix) throws SQLException {
        PathPrefix filter = PathPrefix.of(prefix);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM thumbnails t WHERE t.hash IN ("
                        + affectedImageHashesSql(filter) + ")")) {
            bindAffectedImageHashes(ps, filter);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Counts the face sub-images that would be removed for the given path
     * prefix.
     *
     * @param prefix the photo/video path prefix; must not be blank
     * @return the number of faces stored on affected images
     * @throws SQLException on database error
     */
    public int countAffectedFaces(String prefix) throws SQLException {
        PathPrefix filter = PathPrefix.of(prefix);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM faces f WHERE f.image_hash IN ("
                        + affectedImageHashesSql(filter) + ")")) {
            bindAffectedImageHashes(ps, filter);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Deletes the images affected by the given path prefix (cascading to their
     * paths, thumbnails, faces and frame links).
     *
     * @param prefix the photo/video path prefix; must not be blank
     * @return the number of deleted images
     * @throws SQLException on database error
     */
    public int deleteAffectedImages(String prefix) throws SQLException {
        PathPrefix filter = PathPrefix.of(prefix);
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM images WHERE hash IN (" + affectedImageHashesSql(filter) + ")")) {
            bindAffectedImageHashes(ps, filter);
            return ps.executeUpdate();
        }
    }

    /**
     * Deletes the videos affected by the given path prefix (cascading to their
     * paths and frame links).
     *
     * @param prefix the video path prefix; must not be blank
     * @return the number of deleted videos
     * @throws SQLException on database error
     */
    public int deleteAffectedVideos(String prefix) throws SQLException {
        PathPrefix filter = PathPrefix.of(prefix);
        try (PreparedStatement ps = conn.prepareStatement(deleteAffectedVideosSql(filter))) {
            filter.bind(ps, 1);
            return ps.executeUpdate();
        }
    }

    /**
     * Returns the statement counting the videos whose stored path starts with
     * the prefix.
     *
     * @param filter the path prefix to match
     * @return the SQL counting the affected videos
     */
    static String countAffectedVideosSql(PathPrefix filter) {
        return "SELECT COUNT(DISTINCT hash) FROM video_paths WHERE " + filter.condition("path");
    }

    /**
     * Returns the statement deleting the videos whose stored path starts with
     * the prefix.
     *
     * @param filter the path prefix to match
     * @return the SQL deleting the affected videos
     */
    static String deleteAffectedVideosSql(PathPrefix filter) {
        return "DELETE FROM videos WHERE hash IN (SELECT DISTINCT hash FROM video_paths"
                + " WHERE " + filter.condition("path") + ")";
    }

    /**
     * Binds the two path ranges of an {@link #affectedImageHashesSql} statement,
     * in the order the ranges appear in it.
     *
     * @param ps     the prepared statement to bind
     * @param filter the path prefix both ranges match
     * @throws SQLException on database error
     */
    private static void bindAffectedImageHashes(PreparedStatement ps, PathPrefix filter) throws SQLException {
        filter.bind(ps, filter.bind(ps, 1));
    }
}
