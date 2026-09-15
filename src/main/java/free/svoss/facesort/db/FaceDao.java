package free.svoss.facesort.db;

import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.util.EmbeddingUtils;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data access object for the faces table.
 */
public class FaceDao {

    private final Connection conn;

    public FaceDao(Connection conn) {
        this.conn = conn;
    }

    /**
     * Inserts a new face record. Returns the generated id.
     *
     * @throws IllegalArgumentException if the face or its sub-image JPEG is null
     */
    public long insert(FaceRecord face) throws SQLException {
        if (face == null) {
            throw new IllegalArgumentException("face must not be null");
        }
        if (face.subImageJpg() == null) {
            throw new IllegalArgumentException(
                    "subImageJpg must not be null: the faces table requires a thumbnail JPEG (sub_image_jpg NOT NULL)");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO faces (image_hash, bbox_x, bbox_y, bbox_w, bbox_h, confidence, embedding, sub_image_jpg, name_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, face.imageHash());
            ps.setInt(2, face.bboxX());
            ps.setInt(3, face.bboxY());
            ps.setInt(4, face.bboxW());
            ps.setInt(5, face.bboxH());
            ps.setDouble(6, face.confidence());
            ps.setBytes(7, EmbeddingUtils.floatArrayToBytes(face.embedding()));
            ps.setBytes(8, face.subImageJpg());
            if (face.nameId() != null) {
                ps.setLong(9, face.nameId());
            } else {
                ps.setNull(9, Types.INTEGER);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned");
            }
        }
    }

    /**
     * Returns all faces for a given image hash.
     */
    public List<FaceRecord> findByImageHash(String imageHash) throws SQLException {
        List<FaceRecord> faces = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, image_hash, bbox_x, bbox_y, bbox_w, bbox_h, confidence, embedding, sub_image_jpg, name_id "
                + "FROM faces WHERE image_hash = ?")) {
            ps.setString(1, imageHash);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                faces.add(mapRow(rs));
            }
        }
        return faces;
    }

    /**
     * Returns all unnamed faces (name_id IS NULL).
     */
    public List<FaceRecord> findUnnamed() throws SQLException {
        List<FaceRecord> faces = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                "SELECT id, image_hash, bbox_x, bbox_y, bbox_w, bbox_h, confidence, embedding, sub_image_jpg, name_id "
                + "FROM faces WHERE name_id IS NULL")) {
            while (rs.next()) {
                faces.add(mapRow(rs));
            }
        }
        return faces;
    }

    /**
     * Returns up to {@code limit} unnamed faces chosen at random.
     *
     * <p>A non-positive limit yields an empty list (SQLite would otherwise
     * interpret a negative LIMIT as "unbounded").</p>
     *
     * @param limit maximum number of faces to return; must not be negative
     * @return up to {@code limit} random unnamed faces
     * @throws SQLException on database error
     */
    public List<FaceRecord> findRandomUnnamed(int limit) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        List<FaceRecord> faces = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, image_hash, bbox_x, bbox_y, bbox_w, bbox_h, confidence, embedding, sub_image_jpg, name_id "
                + "FROM faces WHERE name_id IS NULL ORDER BY RANDOM() LIMIT ?")) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                faces.add(mapRow(rs));
            }
        }
        return faces;
    }

    /**
     * Returns all faces with a given name_id.
     */
    public List<FaceRecord> findByNameId(long nameId) throws SQLException {
        List<FaceRecord> faces = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, image_hash, bbox_x, bbox_y, bbox_w, bbox_h, confidence, embedding, sub_image_jpg, name_id "
                + "FROM faces WHERE name_id = ?")) {
            ps.setLong(1, nameId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                faces.add(mapRow(rs));
            }
        }
        return faces;
    }

    /**
     * Returns all faces (for clustering, etc.).
     */
    public List<FaceRecord> findAll() throws SQLException {
        List<FaceRecord> faces = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                "SELECT id, image_hash, bbox_x, bbox_y, bbox_w, bbox_h, confidence, embedding, sub_image_jpg, name_id "
                + "FROM faces")) {
            while (rs.next()) {
                faces.add(mapRow(rs));
            }
        }
        return faces;
    }

    /**
     * Returns a face by its id.
     */
    public Optional<FaceRecord> findById(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, image_hash, bbox_x, bbox_y, bbox_w, bbox_h, confidence, embedding, sub_image_jpg, name_id "
                + "FROM faces WHERE id = ?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
            return Optional.empty();
        }
    }

    /**
     * Assigns a name to a face.
     */
    public void assignName(long faceId, long nameId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE faces SET name_id = ? WHERE id = ?")) {
            ps.setLong(1, nameId);
            ps.setLong(2, faceId);
            ps.executeUpdate();
        }
    }

    /**
     * Counts faces with a given name_id.
     */
    public int countByNameId(long nameId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM faces WHERE name_id = ?")) {
            ps.setLong(1, nameId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Reassigns all faces from one name to another (for merge).
     */
    public void reassignAll(long fromNameId, long toNameId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE faces SET name_id = ? WHERE name_id = ?")) {
            ps.setLong(1, toNameId);
            ps.setLong(2, fromNameId);
            ps.executeUpdate();
        }
    }

    /**
     * Deletes a face by id.
     */
    public void delete(long faceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM faces WHERE id = ?")) {
            ps.setLong(1, faceId);
            ps.executeUpdate();
        }
    }

    private FaceRecord mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String imageHash = rs.getString("image_hash");
        int bboxX = rs.getInt("bbox_x");
        int bboxY = rs.getInt("bbox_y");
        int bboxW = rs.getInt("bbox_w");
        int bboxH = rs.getInt("bbox_h");
        double confidence = rs.getDouble("confidence");
        float[] embedding = EmbeddingUtils.bytesToFloatArray(rs.getBytes("embedding"));
        byte[] subImageJpg = rs.getBytes("sub_image_jpg");
        long nameIdRaw = rs.getLong("name_id");
        Long nameId = rs.wasNull() ? null : nameIdRaw;

        return new FaceRecord(id, imageHash, bboxX, bboxY, bboxW, bboxH, confidence, embedding, subImageJpg, nameId);
    }
}
