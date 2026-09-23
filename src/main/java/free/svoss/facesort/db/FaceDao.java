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

    /**
     * Number of {@code ?} placeholders in {@link #pathFilterClause()}, all bound
     * to the same prefix by {@link #bindPathFilter}. The optional LIMIT clause
     * of {@link #findRandomUnnamed} starts directly after them.
     */
    private static final int PATH_FILTER_PLACEHOLDERS = 5;

    /**
     * The column list every {@code SELECT} projects — the ten columns that
     * {@link #mapRow} reads by name. Kept in one place so the queries cannot
     * drift apart; {@code FaceDaoTest} pins it to the columns {@link #mapRow}
     * reads and to the physical {@code faces} schema.
     */
    static final String SELECT_COLUMNS =
            "id, image_hash, bbox_x, bbox_y, bbox_w, bbox_h, confidence, embedding, sub_image_jpg, name_id";

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
                "SELECT " + SELECT_COLUMNS + " FROM faces WHERE image_hash = ?")) {
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
     *
     * @return all unnamed faces
     * @throws SQLException on database error
     */
    public List<FaceRecord> findUnnamed() throws SQLException {
        return findUnnamed(null);
    }

    /**
     * Returns all unnamed faces (name_id IS NULL), optionally restricted to
     * images that have at least one stored photo path starting with the given
     * prefix, or (for video frames) whose video file path starts with it.
     * A {@code null} or blank prefix disables the restriction.
     *
     * @param pathPrefix path prefix the stored photo/video path must start with,
     *                   or {@code null}/{@code ""} to return all unnamed faces
     * @return the matching unnamed faces
     * @throws SQLException on database error
     */
    public List<FaceRecord> findUnnamed(String pathPrefix) throws SQLException {
        List<FaceRecord> faces = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + SELECT_COLUMNS + " FROM faces WHERE name_id IS NULL" + pathFilterClause())) {
            bindPathFilter(ps, pathPrefix);
            ResultSet rs = ps.executeQuery();
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
        return findRandomUnnamed(limit, null);
    }

    /**
     * Returns up to {@code limit} unnamed faces chosen at random, optionally
     * restricted to images that have at least one stored photo path starting
     * with the given prefix, or (for video frames) whose video file path
     * starts with it. A {@code null} or blank prefix disables the
     * restriction.
     *
     * <p>A non-positive limit yields an empty list (SQLite would otherwise
     * interpret a negative LIMIT as "unbounded").</p>
     *
     * @param limit      maximum number of faces to return; must not be negative
     * @param pathPrefix path prefix the stored photo/video path must start with,
     *                   or {@code null}/{@code ""} to return any random faces
     * @return up to {@code limit} random unnamed faces
     * @throws SQLException on database error
     */
    public List<FaceRecord> findRandomUnnamed(int limit, String pathPrefix) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        List<FaceRecord> faces = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + SELECT_COLUMNS + " FROM faces WHERE name_id IS NULL" + pathFilterClause()
                + " ORDER BY RANDOM() LIMIT ?")) {
            bindPathFilter(ps, pathPrefix);
            ps.setInt(1 + PATH_FILTER_PLACEHOLDERS, limit);
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
                "SELECT " + SELECT_COLUMNS + " FROM faces WHERE name_id = ?")) {
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
                "SELECT " + SELECT_COLUMNS + " FROM faces")) {
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
                "SELECT " + SELECT_COLUMNS + " FROM faces WHERE id = ?")) {
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
     * Sets the name of every face of the given image that is tagged with the
     * given name back to NULL, leaving those faces unnamed.
     *
     * @param imageHash content hash of the image; must not be null
     * @param nameId    the name to remove from the image's faces
     * @return the number of faces that were untagged
     * @throws SQLException on database error
     */
    public int untagFacesFromImage(String imageHash, long nameId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE faces SET name_id = NULL WHERE image_hash = ? AND name_id = ?")) {
            ps.setString(1, imageHash);
            ps.setLong(2, nameId);
            return ps.executeUpdate();
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

    /**
     * Builds the SQL fragment that restricts results to images having at least
     * one stored photo path starting with a given prefix, or (for video
     * frames) at least one stored path of the linked video starting with the
     * prefix. The fragment contains {@link #PATH_FILTER_PLACEHOLDERS} {@code ?}
     * placeholders, all bound to the same prefix value by
     * {@link #bindPathFilter(PreparedStatement, String)}.
     *
     * @return the WHERE fragment, always starting with {@code " AND "}
     */
    private static String pathFilterClause() {
        return " AND (? IS NULL OR EXISTS ("
                + "SELECT 1 FROM image_paths p "
                + "WHERE p.hash = faces.image_hash "
                + "AND substr(p.path, 1, length(?)) = ?)"
                + " OR EXISTS ("
                + "SELECT 1 FROM video_frames vf "
                + "JOIN video_paths vp ON vp.hash = vf.video_hash "
                + "WHERE vf.frame_hash = faces.image_hash "
                + "AND substr(vp.path, 1, length(?)) = ?))";
    }

    /**
     * Binds the path-prefix placeholders produced by
     * {@link #pathFilterClause()} to the given prefix. The prefix is trimmed;
     * a {@code null} or blank prefix leaves the clause disabled.
     *
     * <p>The clause's placeholders live at indexes 1..5, so a query that adds
     * further placeholders (e.g. LIMIT) must start them at index 6.</p>
     *
     * @param ps         the prepared statement to bind
     * @param pathPrefix the trimmed prefix, or {@code null} for no filter
     * @throws SQLException on database error
     */
    private static void bindPathFilter(PreparedStatement ps, String pathPrefix) throws SQLException {
        String prefix = pathPrefix == null ? null : pathPrefix.trim();
        for (int i = 1; i <= 5; i++) {
            if (prefix == null || prefix.isEmpty()) {
                ps.setNull(i, Types.VARCHAR);
            } else {
                ps.setString(i, prefix);
            }
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
