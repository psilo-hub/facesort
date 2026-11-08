package free.svoss.facesort.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * Loads the rejections of many names in one chunked query set, keyed by
     * name id, so a scan over every name does not issue one query per name.
     *
     * <p>Every requested name is a key of the result, mapped to an empty set
     * when the user has not rejected any face for it.</p>
     *
     * @param nameIds the names whose rejections should be loaded; must not be
     *                {@code null}
     * @return the rejected face ids per name, in request order
     * @throws SQLException on database error
     */
    public Map<Long, Set<Long>> findFaceIdsByNameIds(List<Long> nameIds) throws SQLException {
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(nameIds));
        Map<Long, Set<Long>> byName = new LinkedHashMap<>();
        for (Long nameId : distinct) {
            byName.put(nameId, new HashSet<>());
        }
        for (List<Long> chunk : SqlIdChunks.partition(distinct)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT face_id, name_id FROM not_matches WHERE name_id IN ("
                            + SqlIdChunks.placeholders(chunk.size()) + ")")) {
                for (int i = 0; i < chunk.size(); i++) {
                    ps.setLong(i + 1, chunk.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byName.get(rs.getLong("name_id")).add(rs.getLong("face_id"));
                    }
                }
            }
        }
        return byName;
    }
}
