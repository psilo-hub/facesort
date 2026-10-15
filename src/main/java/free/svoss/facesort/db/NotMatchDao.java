package free.svoss.facesort.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 * Data access object for the not_matches table.
 * Records face/name combinations the user decided are NOT the same person,
 * so the naming tabs stop offering that face for that name.
 */
public class NotMatchDao {

    private final Connection conn;

    public NotMatchDao(Connection conn) {
        this.conn = conn;
    }

    /**
     * Records that the face is not the given person. Re-recording the same
     * decision is a no-op, so retries and double clicks cannot create
     * duplicate rows.
     */
    public void insert(long faceId, long nameId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO not_matches (face_id, name_id) VALUES (?, ?)")) {
            ps.setLong(1, faceId);
            ps.setLong(2, nameId);
            ps.executeUpdate();
        }
    }

    /**
     * Checks whether the face was rejected as "not the same person" for the
     * given name. Rejections are per name: saying "not Alice" leaves the face
     * a candidate for Bob.
     */
    public boolean isNotMatch(long faceId, long nameId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM not_matches WHERE face_id = ? AND name_id = ?")) {
            ps.setLong(1, faceId);
            ps.setLong(2, nameId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Returns the ids of all faces the user rejected for the given name.
     *
     * @param nameId id of the name whose rejections should be returned
     * @return the rejected face ids, empty when the name has none
     */
    public Set<Long> findFaceIdsByNameId(long nameId) throws SQLException {
        Set<Long> faceIds = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT face_id FROM not_matches WHERE name_id = ?")) {
            ps.setLong(1, nameId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    faceIds.add(rs.getLong("face_id"));
                }
            }
        }
        return faceIds;
    }
}
