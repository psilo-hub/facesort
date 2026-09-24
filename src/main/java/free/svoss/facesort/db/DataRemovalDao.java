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
 * <p>The prefix is compared byte-wise against the stored path string via
 * {@code substr(path, 1, length(?)) = ?}, the same semantics the path filter of
 * the tagging views uses.</p>
 */
public class DataRemovalDao {

    /**
     * The image hashes a prefix-based removal would delete: photos whose stored
     * path starts with the prefix, plus the frames sampled from matching videos
     * that are not also photos. Contains four {@code ?} placeholders for the
     * same prefix, two per subquery.
     */
    private static final String AFFECTED_IMAGE_HASHES = """
            SELECT DISTINCT ip.hash
            FROM image_paths ip
            WHERE substr(ip.path, 1, length(?)) = ?
            UNION
            SELECT DISTINCT vf.frame_hash
            FROM video_frames vf
            JOIN video_paths vp ON vp.hash = vf.video_hash
            WHERE substr(vp.path, 1, length(?)) = ?
              AND NOT EXISTS (SELECT 1 FROM image_paths op WHERE op.hash = vf.frame_hash)
            """;

    private final Connection conn;

    public DataRemovalDao(Connection conn) {
        this.conn = conn;
    }

    /**
     * Counts the images that would be removed for the given path prefix.
     *
     * @param prefix the photo/video path prefix; must not be blank
     * @return the number of affected images
     * @throws SQLException on database error
     */
    public int countAffectedImages(String prefix) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM (" + AFFECTED_IMAGE_HASHES + ")")) {
            bindPrefix(ps, prefix, 4);
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
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(DISTINCT hash) FROM video_paths"
                        + " WHERE substr(path, 1, length(?)) = ?")) {
            bindPrefix(ps, prefix, 2);
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
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM thumbnails t WHERE t.hash IN (" + AFFECTED_IMAGE_HASHES + ")")) {
            bindPrefix(ps, prefix, 4);
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
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM faces f WHERE f.image_hash IN (" + AFFECTED_IMAGE_HASHES + ")")) {
            bindPrefix(ps, prefix, 4);
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
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM images WHERE hash IN (" + AFFECTED_IMAGE_HASHES + ")")) {
            bindPrefix(ps, prefix, 4);
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
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM videos WHERE hash IN (SELECT DISTINCT hash FROM video_paths"
                        + " WHERE substr(path, 1, length(?)) = ?)")) {
            bindPrefix(ps, prefix, 2);
            return ps.executeUpdate();
        }
    }

    private static void bindPrefix(PreparedStatement ps, String prefix, int count) throws SQLException {
        for (int i = 1; i <= count; i++) {
            ps.setString(i, prefix);
        }
    }
}