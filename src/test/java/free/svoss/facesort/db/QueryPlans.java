package free.svoss.facesort.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test seam for reading the {@code EXPLAIN QUERY PLAN} output of a statement, so
 * a DAO test can pin that a query is served by an index. Only the access type
 * is asserted, never the exact plan text, which differs between SQLite
 * versions.
 */
final class QueryPlans {

    private QueryPlans() {
    }

    /**
     * Returns the plan of the given statement, one entry per row of
     * {@code EXPLAIN QUERY PLAN}.
     *
     * @param connection the connection to plan on
     * @param sql        the statement to plan, without the EXPLAIN prefix
     * @return the trimmed plan lines
     * @throws SQLException on database error
     */
    static List<String> of(Connection connection, String sql) throws SQLException {
        List<String> plan = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
            while (rs.next()) {
                plan.add(rs.getString("detail").trim());
            }
        }
        return plan;
    }

    /**
     * Asserts that the plan reaches every given table reference — a table name
     * or the alias a statement gave it — through an index search, and never
     * through a scan of all its rows (with or without a covering index).
     *
     * @param plan      the plan returned by {@link #of}
     * @param sql       the planned statement, quoted in failure messages
     * @param tableRefs the table names / aliases that must be index-searched
     */
    static void assertSearchedByIndex(List<String> plan, String sql, String... tableRefs) {
        for (String ref : tableRefs) {
            assertTrue(plan.stream().anyMatch(line -> line.startsWith("SEARCH " + ref + " USING ")),
                    () -> ref + " must be reached by an index search, but this planned:\n"
                            + String.join("\n", plan) + "\n(for: " + sql + ")");
            assertFalse(plan.stream().anyMatch(line -> line.equals("SCAN " + ref)
                            || line.startsWith("SCAN " + ref + " ")),
                    () -> ref + " must not be scanned row by row, but this planned:\n"
                            + String.join("\n", plan) + "\n(for: " + sql + ")");
        }
    }
}
