package free.svoss.facesort.db;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access object for the not_dupes table.
 * Records pairs of names that have been confirmed as NOT duplicates.
 */
public class NotDupeDao {

    private final Connection conn;

    public NotDupeDao(Connection conn) {
        this.conn = conn;
    }

    /**
     * Records that two names are not duplicates.
     * Stores with id_a < id_b to avoid duplicates.
     */
    public void insert(long nameIdA, long nameIdB) throws SQLException {
        long a = Math.min(nameIdA, nameIdB);
        long b = Math.max(nameIdA, nameIdB);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO not_dupes (name_id_a, name_id_b) VALUES (?, ?)")) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            ps.executeUpdate();
        }
    }

    /**
     * Checks if two names are marked as not-duplicates.
     */
    public boolean isNotDupe(long nameIdA, long nameIdB) throws SQLException {
        long a = Math.min(nameIdA, nameIdB);
        long b = Math.max(nameIdA, nameIdB);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM not_dupes WHERE name_id_a = ? AND name_id_b = ?")) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            return ps.executeQuery().next();
        }
    }

    /**
     * Returns all not-dupe pairs involving the given name.
     */
    public List<long[]> findByNameId(long nameId) throws SQLException {
        List<long[]> pairs = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name_id_a, name_id_b FROM not_dupes WHERE name_id_a = ? OR name_id_b = ?")) {
            ps.setLong(1, nameId);
            ps.setLong(2, nameId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                pairs.add(new long[]{rs.getLong("name_id_a"), rs.getLong("name_id_b")});
            }
        }
        return pairs;
    }

    /**
     * Returns all not-dupe pairs.
     */
    public List<long[]> findAll() throws SQLException {
        List<long[]> pairs = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name_id_a, name_id_b FROM not_dupes")) {
            while (rs.next()) {
                pairs.add(new long[]{rs.getLong("name_id_a"), rs.getLong("name_id_b")});
            }
        }
        return pairs;
    }

    /**
     * Removes all not-dupe entries involving the given name (used during merge).
     */
    public void deleteForName(long nameId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM not_dupes WHERE name_id_a = ? OR name_id_b = ?")) {
            ps.setLong(1, nameId);
            ps.setLong(2, nameId);
            ps.executeUpdate();
        }
    }
}
