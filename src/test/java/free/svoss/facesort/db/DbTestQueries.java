package free.svoss.facesort.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared test queries for data whose DAO methods are production-unreachable and
 * therefore no longer exposed. Lets service-level tests stay readable without
 * reintroducing test-only API on the DAOs.
 */
public final class DbTestQueries {

    private DbTestQueries() {
    }

    /**
     * Returns the hash of every stored image, in rowid order.
     *
     * @param connection the connection to query on
     * @return all image hashes
     * @throws SQLException on database error
     */
    public static List<String> allImageHashes(Connection connection) throws SQLException {
        List<String> hashes = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT hash FROM images")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hashes.add(rs.getString("hash"));
                }
            }
        }
        return hashes;
    }
}
