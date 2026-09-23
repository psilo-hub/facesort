package free.svoss.facesort.db;

import free.svoss.facesort.model.ImageRecord;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
     * Returns an ImageRecord by hash, or empty if not found.
     */
    public Optional<ImageRecord> findByHash(String hash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT hash, detection_ts, criteria_json, face_count FROM images WHERE hash = ?")) {
            ps.setString(1, hash);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
            return Optional.empty();
        }
    }

    /**
     * Inserts a new image row. Returns the hash.
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

    /**
     * Returns all image hashes.
     */
    public List<String> getAllHashes() throws SQLException {
        List<String> hashes = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT hash FROM images")) {
            while (rs.next()) {
                hashes.add(rs.getString("hash"));
            }
        }
        return hashes;
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

    private ImageRecord mapRow(ResultSet rs) throws SQLException {
        return new ImageRecord(
                rs.getString("hash"),
                rs.getLong("detection_ts"),
                rs.getString("criteria_json"),
                rs.getInt("face_count")
        );
    }
}
