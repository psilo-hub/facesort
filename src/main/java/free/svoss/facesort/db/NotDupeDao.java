package free.svoss.facesort.db;

import free.svoss.facesort.model.NamePair;

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
     * Returns all not-dupe pairs.
     */
    public List<NamePair> findAll() throws SQLException {
        List<NamePair> pairs = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name_id_a, name_id_b FROM not_dupes")) {
            while (rs.next()) {
                pairs.add(new NamePair(rs.getLong("name_id_a"), rs.getLong("name_id_b")));
            }
        }
        return pairs;
    }
}
