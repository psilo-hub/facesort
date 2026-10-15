package free.svoss.facesort.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Data access object for the {@code meta} key/value table.
 *
 * <p>Holds bookkeeping flags that are not part of the user's data, such as
 * whether a one-time data migration has already run. Values are opaque strings;
 * callers decide what they mean.</p>
 */
public class MetaDao {

    private final Connection conn;

    public MetaDao(Connection conn) {
        this.conn = conn;
    }

    /**
     * Returns the value stored under the given key.
     *
     * @param key the key to look up; must not be null
     * @return the stored value, or empty when the key was never written
     */
    public Optional<String> get(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("value")) : Optional.empty();
            }
        }
    }

    /**
     * Stores a value under the given key, replacing any previous value.
     *
     * @param key   the key to write; must not be null
     * @param value the value to store; must not be null
     */
    public void put(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    /**
     * Removes a key and its value.
     *
     * @param key the key to remove; must not be null
     */
    public void remove(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }
}
