package free.svoss.facesort.db;

import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.util.EmbeddingUtils;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Data access object for the names table.
 */
public class NameDao {

    /**
     * SELECT prefix projecting each name together with the correlated count of
     * faces currently tagged with it. All three read methods append their own
     * WHERE/ORDER clause, and all three map rows through {@link #mapRow}.
     */
    private static final String SELECT_WITH_FACE_COUNT =
            "SELECT id, name, "
                    + "(SELECT COUNT(*) FROM faces f WHERE f.name_id = names.id) AS face_count "
                    + "FROM names";

    private final Connection conn;

    public NameDao(Connection conn) {
        this.conn = conn;
    }

    /**
     * Inserts a new name. Returns the generated id.
     */
    public long insert(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO names (name) VALUES (?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
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
     * Finds a name by exact string. Returns empty if not found.
     */
    public Optional<NameRecord> findByName(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                SELECT_WITH_FACE_COUNT + " WHERE name = ?")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
            return Optional.empty();
        }
    }

    /**
     * Returns a name by id.
     */
    public Optional<NameRecord> findById(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                SELECT_WITH_FACE_COUNT + " WHERE id = ?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
            return Optional.empty();
        }
    }

    /**
     * Returns all names.
     */
    public List<NameRecord> findAll() throws SQLException {
        List<NameRecord> names = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(SELECT_WITH_FACE_COUNT + " ORDER BY name")) {
            while (rs.next()) {
                names.add(mapRow(rs));
            }
        }
        return names;
    }

    /**
     * Renames the name with the given id.
     *
     * @param id      id of the name to rename
     * @param newName the new display name; must satisfy the UNIQUE constraint
     *                on {@code names.name}
     * @throws SQLException if the row does not exist, the name is blank, or the
     *                      UNIQUE constraint is violated
     */
    public void rename(long id, String newName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE names SET name = ? WHERE id = ?")) {
            ps.setString(1, newName);
            ps.setLong(2, id);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new SQLException("No name with id " + id);
            }
        }
    }

    /**
     * Deletes a name by id. Cascades will handle not_dupes and set face name_ids to NULL.
     */
    public void delete(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM names WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /**
     * Returns the count of names.
     */
    public int count() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM names")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Returns the number of names that have at least one face tagged with them.
     * A cheap {@code COUNT(*)} subquery used for the backlog counter without
     * loading any name rows.
     *
     * @return the number of names with tagged faces
     * @throws SQLException on database error
     */
    public long countWithFaces() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(DISTINCT name_id) FROM faces WHERE name_id IS NOT NULL")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /**
     * A name's average embedding together with the face count at the time it
     * was computed. The face count lets callers detect when the average is
     * stale because faces have been tagged or untagged since it was stored.
     */
    public record NameAverage(float[] embedding, int faceCount) {
    }

    /**
     * Loads a name's stored average embedding, or empty when none is stored.
     * Callers should compare {@link NameAverage#faceCount()} against the
     * current face count for the name to detect staleness.
     *
     * @param nameId id of the name
     * @return the stored average and its face count, or empty
     * @throws SQLException on database error
     */
    public Optional<NameAverage> loadNameAverage(long nameId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT embedding, face_count FROM name_averages WHERE name_id = ?")) {
            ps.setLong(1, nameId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new NameAverage(
                            EmbeddingUtils.bytesToFloatArray(rs.getBytes("embedding")),
                            rs.getInt("face_count")));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Stores a name's average embedding, replacing any existing row for the
     * same name.
     *
     * @param nameId    id of the name
     * @param embedding the average embedding; must not be null
     * @param faceCount number of faces the average was computed over
     * @throws SQLException on database error
     */
    public void storeNameAverage(long nameId, float[] embedding, int faceCount)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO name_averages (name_id, embedding, face_count) "
                        + "VALUES (?, ?, ?)")) {
            ps.setLong(1, nameId);
            ps.setBytes(2, EmbeddingUtils.floatArrayToBytes(embedding));
            ps.setInt(3, faceCount);
            ps.executeUpdate();
        }
    }

    /** Deletes every stored average, used when a face write invalidates them all. */
    public void deleteAllNameAverages() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM name_averages");
        }
    }

    /**
     * Loads stored averages for many names in one chunked query set.
     *
     * @param nameIds the name ids to load; must not be null
     * @return the stored average per name id, or null when none is stored
     * @throws SQLException on database error
     */
    public Map<Long, NameAverage> loadNameAverages(List<Long> nameIds) throws SQLException {
        Map<Long, NameAverage> result = new LinkedHashMap<>();
        for (Long nameId : nameIds) {
            result.put(nameId, null);
        }
        if (nameIds.isEmpty()) {
            return result;
        }
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(nameIds));
        for (List<Long> chunk : SqlIdChunks.partition(distinct)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name_id, embedding, face_count FROM name_averages WHERE name_id IN ("
                            + SqlIdChunks.placeholders(chunk.size()) + ")")) {
                for (int i = 0; i < chunk.size(); i++) {
                    ps.setLong(i + 1, chunk.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getLong("name_id"), new NameAverage(
                                EmbeddingUtils.bytesToFloatArray(rs.getBytes("embedding")),
                                rs.getInt("face_count")));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Stores averages for many names, replacing any existing rows.
     *
     * @param averages the averages to store, mapped by name id; must not be null
     * @throws SQLException on database error
     */
    public void storeNameAverages(Map<Long, NameAverage> averages) throws SQLException {
        for (Map.Entry<Long, NameAverage> entry : averages.entrySet()) {
            storeNameAverage(entry.getKey(), entry.getValue().embedding(),
                    entry.getValue().faceCount());
        }
    }

    private NameRecord mapRow(ResultSet rs) throws SQLException {
        return new NameRecord(rs.getLong("id"), rs.getString("name"), null, rs.getInt("face_count"));
    }
}
