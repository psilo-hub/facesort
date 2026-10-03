package free.svoss.facesort.db;

import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.util.EmbeddingUtils;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Data access object for the faces table.
 */
public class FaceDao {

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
        PathPrefix filter = PathPrefix.of(pathPrefix);
        List<FaceRecord> faces = new ArrayList<>();
        try (PreparedStatement ps = prepareWithPathFilter(
                "SELECT " + SELECT_COLUMNS + " FROM faces WHERE name_id IS NULL", filter)) {
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
     * <p>Sampling happens in two steps to avoid SQLite sorting the whole
     * matching row set (blobs included) with {@code ORDER BY RANDOM()}: the
     * matching ids alone are read, a random subset is picked in Java via
     * {@link #sampleRandom}, and only those faces are fetched by primary key.</p>
     *
     * <p>A non-positive limit yields an empty list.</p>
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
     * <p>Sampling happens in two steps to avoid SQLite sorting the whole
     * matching row set (blobs included) with {@code ORDER BY RANDOM()}: the
     * matching ids alone are read, a random subset is picked in Java via
     * {@link #sampleRandom}, and only those faces are fetched by primary key.
     * Faces tagged or deleted by another worker between the id pick and the
     * fetch are silently dropped, so a result may contain fewer than
     * {@code limit} entries under concurrency.</p>
     *
     * <p>A non-positive limit yields an empty list.</p>
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
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = prepareWithPathFilter(
                "SELECT id FROM faces WHERE name_id IS NULL", PathPrefix.of(pathPrefix))) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        List<Long> sampled = sampleRandom(ids, limit, new Random());
        return findByIds(sampled);
    }

    public List<free.svoss.facesort.model.FaceThumb> findRandomUnnamedThumbs(int limit, String pathPrefix)
            throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = prepareWithPathFilter(
                "SELECT id FROM faces WHERE name_id IS NULL", PathPrefix.of(pathPrefix))) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        List<Long> sampled = sampleRandom(ids, limit, new Random());
        return findThumbsByIds(sampled);
    }

    /**
     * Picks up to {@code limit} distinct ids from {@code candidates}, chosen
     * uniformly at random. The selection is a partial Fisher–Yates shuffle:
     * only the first {@code limit} positions are randomized, so the cost is
     * proportional to the sample size, not the candidate count.
     *
     * <p>This is the Java-side replacement for SQLite's {@code ORDER BY
     * RANDOM()} sampling, which previously forced the database to sort the
     * whole matching row set (including the embedding / thumbnail blobs) on
     * every call. A deterministic {@code Random} makes the call reproducible
     * for tests.</p>
     *
     * @param candidates ids to sample from; must not be {@code null}
     * @param limit      maximum number of ids to return; a non-positive limit
     *                   yields an empty list
     * @param rng        the random source
     * @return up to {@code limit} distinct candidate ids, in random order; the
     *         whole candidate list when it is not larger than the limit
     */
    static List<Long> sampleRandom(List<Long> candidates, int limit, Random rng) {
        if (limit <= 0 || candidates.isEmpty()) {
            return List.of();
        }
        List<Long> shuffled = new ArrayList<>(candidates);
        if (shuffled.size() <= limit) {
            return shuffled;
        }
        List<Long> sampled = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            int j = i + rng.nextInt(shuffled.size() - i);
            Collections.swap(shuffled, i, j);
            sampled.add(shuffled.get(i));
        }
        return sampled;
    }

    /**
     * Maximum number of ids fetched per {@code WHERE id IN (...)} lookup, kept
     * well below SQLite's variable-number limit so a large {@code limit} cannot
     * exhaust it.
     */
    private static final int MAX_IN_IDS = 500;

    /**
     * Loads the faces with the given ids by primary key.
     *
     * @param ids the ids to load; must not be {@code null}
     * @return the matching faces, in the given id order; ids that no longer
     *         exist (deleted between the id pick and this fetch) are skipped
     * @throws SQLException on database error
     */
    private List<FaceRecord> findByIds(List<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, FaceRecord> byId = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += MAX_IN_IDS) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + MAX_IN_IDS));
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + SELECT_COLUMNS + " FROM faces WHERE id IN ("
                            + placeholders(chunk.size()) + ") AND name_id IS NULL")) {
                for (int i = 0; i < chunk.size(); i++) {
                    ps.setLong(i + 1, chunk.get(i));
                }
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    FaceRecord face = mapRow(rs);
                    byId.put(face.id(), face);
                }
            }
        }
        List<FaceRecord> faces = new ArrayList<>(ids.size());
        for (Long id : ids) {
            FaceRecord face = byId.get(id);
            if (face != null) {
                faces.add(face);
            }
        }
        return faces;
    }

    /**
     * Loads face thumbnails with the given ids by primary key (no embeddings).
     */
    private List<free.svoss.facesort.model.FaceThumb> findThumbsByIds(List<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, free.svoss.facesort.model.FaceThumb> byId = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += MAX_IN_IDS) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + MAX_IN_IDS));
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, image_hash, bbox_x, bbox_y, bbox_w, bbox_h, confidence, sub_image_jpg, name_id"
                            + " FROM faces WHERE id IN (" + placeholders(chunk.size()) + ") AND name_id IS NULL")) {
                for (int i = 0; i < chunk.size(); i++) {
                    ps.setLong(i + 1, chunk.get(i));
                }
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    byId.put(rs.getLong("id"), mapThumbRow(rs));
                }
            }
        }
        List<free.svoss.facesort.model.FaceThumb> faces = new ArrayList<>(ids.size());
        for (Long id : ids) {
            free.svoss.facesort.model.FaceThumb face = byId.get(id);
            if (face != null) {
                faces.add(face);
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
     * Loads the faces of every given name in one grouped query set, keyed by
     * name id.
     *
     * <p>This is the batch form of {@link #findByNameId}: a caller that needs
     * the faces of many names — averaging each name's embeddings, for
     * instance — would otherwise issue one query per name. Every requested id
     * is a key of the returned map, mapping to an empty list when the name has
     * no faces, so callers never have to null-check.</p>
     *
     * <p>Ids are de-duplicated and issued in chunks of at most
     * {@link #MAX_IN_IDS}, so a large name list cannot exhaust SQLite's
     * variable-number limit. Within a name the faces keep their stored
     * (rowid) order, exactly as {@link #findByNameId} returns them.</p>
     *
     * @param nameIds the name ids to load; must not be {@code null}
     * @return the faces per name id, keyed in the order the ids were requested
     * @throws SQLException on database error
     */
    public Map<Long, List<FaceRecord>> findByNameIds(List<Long> nameIds) throws SQLException {
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(nameIds));
        Map<Long, List<FaceRecord>> byName = new LinkedHashMap<>();
        for (Long nameId : ids) {
            byName.put(nameId, new ArrayList<>());
        }
        for (int from = 0; from < ids.size(); from += MAX_IN_IDS) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + MAX_IN_IDS));
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + SELECT_COLUMNS + " FROM faces WHERE name_id IN ("
                            + placeholders(chunk.size()) + ")")) {
                for (int i = 0; i < chunk.size(); i++) {
                    ps.setLong(i + 1, chunk.get(i));
                }
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    FaceRecord face = mapRow(rs);
                    byName.get(face.nameId()).add(face);
                }
            }
        }
        return byName;
    }

    /**
     * Returns a comma-separated list of {@code count} bind placeholders.
     */
    private static String placeholders(int count) {
        return IntStream.range(0, count).mapToObj(i -> "?").collect(Collectors.joining(","));
    }

    /**
     * Returns the distinct image hashes of the faces with a given name_id, in
     * the order each image's first such face was stored.
     *
     * <p>This is the projection for callers that only need to know <em>which
     * images</em> a name appears in — for example a thumbnail list or an export
     * — rather than the faces themselves. It projects neither
     * {@code embedding} nor {@code sub_image_jpg}, so it never pays the
     * 512-float array plus a JPEG per row that {@link #findByNameId} does; that
     * matters because a name can have thousands of faces.</p>
     *
     * <p>{@code ORDER BY MIN(id)} makes the "first such face" ordering explicit
     * instead of leaving it to whichever scan the query planner picks, and
     * {@code GROUP BY} does the de-duplication the callers used to do in
     * Java.</p>
     *
     * @param nameId id of the name
     * @return the distinct image hashes, in first-face order; empty when the
     *         name has no faces
     * @throws SQLException on database error
     */
    public List<String> findImageHashesByNameId(long nameId) throws SQLException {
        List<String> hashes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT image_hash FROM faces WHERE name_id = ? "
                        + "GROUP BY image_hash ORDER BY MIN(id)")) {
            ps.setLong(1, nameId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                hashes.add(rs.getString(1));
            }
        }
        return hashes;
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
     * prefix. A disabled filter yields no fragment at all.
     *
     * @param filter the path prefix to match
     * @return the WHERE fragment, empty or starting with {@code " AND "}
     */
    static String pathFilterClause(PathPrefix filter) {
        if (filter.isDisabled()) {
            return "";
        }
        return " AND (EXISTS ("
                + "SELECT 1 FROM image_paths p "
                + "WHERE p.hash = faces.image_hash "
                + "AND " + filter.condition("p.path") + ")"
                + " OR EXISTS ("
                + "SELECT 1 FROM video_frames vf "
                + "JOIN video_paths vp ON vp.hash = vf.video_hash "
                + "WHERE vf.frame_hash = faces.image_hash "
                + "AND " + filter.condition("vp.path") + "))";
    }

    /**
     * Prepares the given statement with the path filter appended, and binds its
     * placeholders. Fragment and values come from the same {@link PathPrefix},
     * so a fragment without placeholders is prepared without bound values.
     *
     * @param sql    the statement, ending before the filter
     * @param filter the path prefix to match
     * @return the prepared statement, ready to execute
     * @throws SQLException on database error
     */
    private PreparedStatement prepareWithPathFilter(String sql, PathPrefix filter) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql + pathFilterClause(filter));
        if (!filter.isDisabled()) {
            // The photo path range comes first in the fragment, the video path range second.
            filter.bind(ps, filter.bind(ps, 1));
        }
        return ps;
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

    private free.svoss.facesort.model.FaceThumb mapThumbRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String imageHash = rs.getString("image_hash");
        int bboxX = rs.getInt("bbox_x");
        int bboxY = rs.getInt("bbox_y");
        int bboxW = rs.getInt("bbox_w");
        int bboxH = rs.getInt("bbox_h");
        double confidence = rs.getDouble("confidence");
        byte[] subImageJpg = rs.getBytes("sub_image_jpg");
        long nameIdRaw = rs.getLong("name_id");
        Long nameId = rs.wasNull() ? null : nameIdRaw;

        return new free.svoss.facesort.model.FaceThumb(id, imageHash, bboxX, bboxY, bboxW, bboxH,
                confidence, subImageJpg, nameId);
    }
}
