package free.svoss.facesort.db;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Data access object for the images and image_paths tables.
 */
public class ImageDao {

    private final Connection conn;

    public ImageDao(Connection conn) {
        this.conn = conn;
    }

    /**
     * Checks if an image with the given hash exists.
     */
    public boolean exists(String hash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM images WHERE hash = ?")) {
            ps.setString(1, hash);
            return ps.executeQuery().next();
        }
    }

    /**
     * Adds a path for an existing image hash.
     */
    public void insert(String hash, long detectionTs, String criteriaJson, int faceCount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO images (hash, detection_ts, criteria_json, face_count) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, hash);
            ps.setLong(2, detectionTs);
            ps.setString(3, criteriaJson);
            ps.setInt(4, faceCount);
            ps.executeUpdate();
        }
    }

    /**
     * Adds a path for an existing image hash.
     */
    public void addPath(String hash, String path) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO image_paths (hash, path) VALUES (?, ?)")) {
            ps.setString(1, hash);
            ps.setString(2, path);
            ps.executeUpdate();
        }
    }

    /**
     * Returns all paths for a given image hash.
     */
    public List<String> getPaths(String hash) throws SQLException {
        List<String> paths = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT path FROM image_paths WHERE hash = ?")) {
            ps.setString(1, hash);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                paths.add(rs.getString("path"));
            }
        }
        return paths;
    }

    // ---- Thumbnail operations ----

    /**
     * Saves or replaces a JPEG thumbnail for the given image hash.
     *
     * @param hash     content hash of the image (foreign key to images)
     * @param jpgData  JPEG-encoded thumbnail bytes
     */
    public void saveThumbnail(String hash, byte[] jpgData) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO thumbnails (hash, jpg_data) VALUES (?, ?)")) {
            ps.setString(1, hash);
            ps.setBytes(2, jpgData);
            ps.executeUpdate();
        }
    }

    /**
     * Returns the JPEG thumbnail bytes for the given image hash,
     * or {@code null} if no thumbnail is stored.
     *
     * @param hash content hash of the image
     * @return thumbnail JPEG bytes, or {@code null}
     */
    public byte[] getThumbnail(String hash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT jpg_data FROM thumbnails WHERE hash = ?")) {
            ps.setString(1, hash);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getBytes("jpg_data");
            }
            return null;
        }
    }

    /**
     * Returns the stored JPEG thumbnails of every given image hash, loaded in
     * batched queries.
     *
     * <p>This is the batch form of {@link #getThumbnail(String)}: a caller that
     * needs the thumbnails of many images — the grid of images a name appears
     * in, for instance — would otherwise issue one query per image. Here a
     * single statement loads up to {@link SqlIdChunks#MAX_IN_IDS} hashes and
     * only one extra statement is added per further
     * {@link SqlIdChunks#MAX_IN_IDS}, so the query count no longer grows with
     * the image count one for one.</p>
     *
     * <p>Every requested hash that has a stored thumbnail is a key of the
     * returned map, carrying its bytes. A requested hash with no stored
     * thumbnail is simply <em>absent</em> from the map, so {@code get} on it
     * yields {@code null} — the same result {@link #getThumbnail(String)}
     * returns for a missing thumbnail. A caller therefore cannot tell a missing
     * thumbnail from an unknown hash, and does not need to: both mean "no
     * thumbnail".</p>
     *
     * <p>Hashes are de-duplicated and chunked at {@link SqlIdChunks#MAX_IN_IDS}
     * — the SQLite-safe bind batch size — before they are issued.</p>
     *
     * @param hashes the image hashes to load; must not be {@code null}
     * @return the stored thumbnails by hash; a hash without one is absent
     * @throws SQLException on database error
     */
    public Map<String, byte[]> getThumbnails(List<String> hashes) throws SQLException {
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(hashes));
        Map<String, byte[]> thumbnails = new LinkedHashMap<>();
        for (List<String> chunk : SqlIdChunks.partition(distinct)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT hash, jpg_data FROM thumbnails WHERE hash IN ("
                            + SqlIdChunks.placeholders(chunk.size()) + ")")) {
                for (int i = 0; i < chunk.size(); i++) {
                    ps.setString(i + 1, chunk.get(i));
                }
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    thumbnails.put(rs.getString("hash"), rs.getBytes("jpg_data"));
                }
            }
        }
        return thumbnails;
    }

    /**
     * Checks whether a thumbnail exists for the given image hash.
     *
     * @param hash content hash of the image
     * @return {@code true} if a thumbnail row exists
     */
    public boolean hasThumbnail(String hash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM thumbnails WHERE hash = ?")) {
            ps.setString(1, hash);
            return ps.executeQuery().next();
        }
    }
}
