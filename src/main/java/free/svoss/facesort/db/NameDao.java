package free.svoss.facesort.db;

import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.util.EmbeddingUtils;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data access object for the names table.
 */
public class NameDao {

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
                "SELECT id, name, (SELECT COUNT(*) FROM faces f WHERE f.name_id = names.id) AS face_count "
                        + "FROM names WHERE name = ?")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(new NameRecord(rs.getLong("id"), rs.getString("name"), null, rs.getInt("face_count")));
            }
            return Optional.empty();
        }
    }

    /**
     * Returns a name by id.
     */
    public Optional<NameRecord> findById(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, (SELECT COUNT(*) FROM faces f WHERE f.name_id = names.id) AS face_count "
                        + "FROM names WHERE id = ?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(new NameRecord(rs.getLong("id"), rs.getString("name"), null, rs.getInt("face_count")));
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
             ResultSet rs = stmt.executeQuery("SELECT id, name, (SELECT COUNT(*) FROM faces f WHERE f.name_id = names.id) AS face_count "
                     + "FROM names ORDER BY name")) {
            while (rs.next()) {
                names.add(new NameRecord(rs.getLong("id"), rs.getString("name"), null, rs.getInt("face_count")));
            }
        }
        return names;
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
}
