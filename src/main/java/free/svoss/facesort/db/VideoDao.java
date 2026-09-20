package free.svoss.facesort.db;

import free.svoss.facesort.model.VideoFrameLinkRecord;
import free.svoss.facesort.model.VideoRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data access object for the {@code videos}, {@code video_paths} and
 * {@code video_frames} tables.
 */
public class VideoDao {

    private final Connection conn;

    public VideoDao(Connection conn) {
        this.conn = conn;
    }

    /**
     * Inserts a new video row. Re-importing a known hash is a no-op.
     */
    public void insert(String hash, long detectionTs, String criteriaJson,
                       double durationSecs) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO videos (hash, detection_ts, criteria_json, duration_secs)"
                        + " VALUES (?, ?, ?, ?)")) {
            ps.setString(1, hash);
            ps.setLong(2, detectionTs);
            ps.setString(3, criteriaJson);
            ps.setDouble(4, durationSecs);
            ps.executeUpdate();
        }
    }

    /**
     * Checks if a video with the given hash exists.
     */
    public boolean exists(String hash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM videos WHERE hash = ?")) {
            ps.setString(1, hash);
            return ps.executeQuery().next();
        }
    }

    /**
     * Returns a {@link VideoRecord} by hash, or empty when not found.
     * Linked-frame counts are computed live: {@code frameCount} counts the
     * {@code video_frames} rows, {@code faceCount} sums the faces stored on the
     * linked frame images.
     */
    public Optional<VideoRecord> findByHash(String hash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT v.hash, v.detection_ts, v.criteria_json, v.duration_secs,
                       (SELECT COUNT(*) FROM video_frames vf
                         WHERE vf.video_hash = v.hash) AS frame_count,
                       (SELECT COALESCE(SUM(i.face_count), 0) FROM video_frames vf
                         JOIN images i ON i.hash = vf.frame_hash
                         WHERE vf.video_hash = v.hash) AS face_count
                FROM videos v WHERE v.hash = ?
                """)) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new VideoRecord(
                        rs.getString("hash"),
                        rs.getLong("detection_ts"),
                        rs.getString("criteria_json"),
                        rs.getDouble("duration_secs"),
                        rs.getInt("frame_count"),
                        rs.getInt("face_count")));
            }
        }
    }

    /**
     * Records a file path for a video; duplicate paths are ignored.
     */
    public void addPath(String hash, String path) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO video_paths (hash, path) VALUES (?, ?)")) {
            ps.setString(1, hash);
            ps.setString(2, path);
            ps.executeUpdate();
        }
    }

    /**
     * Returns all known file paths for a video, deduplicated.
     */
    public List<String> getPaths(String hash) throws SQLException {
        List<String> paths = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT path FROM video_paths WHERE hash = ? ORDER BY path")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    paths.add(rs.getString("path"));
                }
            }
        }
        return paths;
    }

    /**
     * Checks whether a video is reachable through the given path.
     */
    public boolean hasPath(String hash, String path) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM video_paths WHERE hash = ? AND path = ?")) {
            ps.setString(1, hash);
            ps.setString(2, path);
            return ps.executeQuery().next();
        }
    }

    /**
     * Links a frame image to a video at the given millisecond offset. A frame
     * may be linked to at most one video — when it already belongs to another
     * video the link is ignored.
     *
     * @return the number of links inserted (1 when linked, 0 when the frame
     *         already belongs to another video)
     */
    public int linkFrame(String frameHash, String videoHash, long timestampMs) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO video_frames (frame_hash, video_hash, timestamp_ms)
                VALUES (?, ?, ?)
                """)) {
            ps.setString(1, frameHash);
            ps.setString(2, videoHash);
            ps.setLong(3, timestampMs);
            return ps.executeUpdate();
        }
    }

    /**
     * Returns the frames linked to a video, in timestamp order.
     */
    public List<VideoFrameLinkRecord> findFramesForVideo(String videoHash) throws SQLException {
        List<VideoFrameLinkRecord> frames = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT frame_hash, video_hash, timestamp_ms FROM video_frames"
                        + " WHERE video_hash = ? ORDER BY timestamp_ms")) {
            ps.setString(1, videoHash);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    frames.add(new VideoFrameLinkRecord(
                            rs.getString("frame_hash"),
                            rs.getString("video_hash"),
                            rs.getLong("timestamp_ms")));
                }
            }
        }
        return frames;
    }

    /**
     * Returns the millisecond offset of a linked frame, or null if not linked.
     */
    public Long findTimestamp(String frameHash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT timestamp_ms FROM video_frames WHERE frame_hash = ?")) {
            ps.setString(1, frameHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("timestamp_ms") : null;
            }
        }
    }

    /**
     * Persists the frame and face counts on the video row after import
     * completes. The row is expected to exist (use
     * {@link #insert(String, long, String, double)} first).
     */
    public void updateVideoCounts(String hash, int frameCount, int faceCount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE videos SET frame_count = ?, face_count = ? WHERE hash = ?")) {
            ps.setInt(1, frameCount);
            ps.setInt(2, faceCount);
            ps.setString(3, hash);
            ps.executeUpdate();
        }
    }

    /**
     * Deletes a video; paths and frame links cascade via foreign keys.
     */
    public void delete(String hash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM videos WHERE hash = ?")) {
            ps.setString(1, hash);
            ps.executeUpdate();
        }
    }
}
