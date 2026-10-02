package free.svoss.facesort.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SqliteErrors}, the one place that knows how sqlite-jdbc
 * spells a failure.
 *
 * <p>The unit cases build {@link SQLiteException}s directly — its
 * {@code (String, SQLiteErrorCode)} constructor is public — and the driver cases
 * drive a real in-memory database so the codes asserted above are pinned to the
 * codes the driver actually produces for those SQL situations.</p>
 */
class SqliteErrorsTest {

    private Database db;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        connection = db.getConnection();
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    // ------------------------------------------------------------------
    // Unit cases: the classification itself
    // ------------------------------------------------------------------

    @Test
    void duplicatePrimaryKeyIsADuplicateContentConflict() {
        assertTrue(SqliteErrors.isDuplicateContentConflict(
                sqliteException(SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY)));
    }

    @Test
    void duplicateUniqueIndexIsADuplicateContentConflict() {
        assertTrue(SqliteErrors.isDuplicateContentConflict(
                sqliteException(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE)));
    }

    @Test
    void notNullViolationIsNotADuplicateContentConflict() {
        assertFalse(SqliteErrors.isDuplicateContentConflict(
                sqliteException(SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL)));
    }

    @Test
    void foreignKeyViolationIsNotADuplicateContentConflict() {
        assertFalse(SqliteErrors.isDuplicateContentConflict(
                sqliteException(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY)));
    }

    @Test
    void triggerAbortIsNotADuplicateContentConflict() {
        assertFalse(SqliteErrors.isDuplicateContentConflict(
                sqliteException(SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER)));
    }

    @Test
    void checkViolationIsNotADuplicateContentConflict() {
        assertFalse(SqliteErrors.isDuplicateContentConflict(
                sqliteException(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK)));
    }

    @Test
    void plainSqlErrorIsNotADuplicateContentConflict() {
        assertFalse(SqliteErrors.isDuplicateContentConflict(
                sqliteException(SQLiteErrorCode.SQLITE_ERROR)));
    }

    @Test
    void diskFullIsNotADuplicateContentConflict() {
        assertFalse(SqliteErrors.isDuplicateContentConflict(
                sqliteException(SQLiteErrorCode.SQLITE_FULL)));
    }

    @Test
    void exceptionFromAnotherDriverIsNotADuplicateContentConflict() {
        assertFalse(SqliteErrors.isDuplicateContentConflict(new SQLException("no driver involved")));
    }

    // ------------------------------------------------------------------
    // Driver cases: the codes the real driver really produces
    // ------------------------------------------------------------------

    /**
     * The load-bearing assumption behind the classification: a duplicate insert
     * into {@code images.hash} really does arrive as
     * {@code SQLITE_CONSTRAINT_PRIMARYKEY}, not as
     * {@code SQLITE_CONSTRAINT_UNIQUE}. Both are accepted, but only a test
     * against the real schema would notice if the driver changed its mind.
     */
    @Test
    void duplicateImageHashArrivesAsPrimaryKeyConstraint() throws Exception {
        execute("INSERT INTO images (hash, detection_ts, criteria_json, face_count) "
                + "VALUES ('h', 1, '{}', 0)");

        SQLException failure = failing("INSERT INTO images (hash) VALUES ('h')");

        assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY, resultCodeOf(failure));
        assertTrue(SqliteErrors.isDuplicateContentConflict(failure));
    }

    /** The counterpart on {@code faces}: a separate unique index, so a different code. */
    @Test
    void duplicateFaceBoundingBoxArrivesAsUniqueConstraint() throws Exception {
        execute("INSERT INTO images (hash) VALUES ('n')");
        execute("INSERT INTO faces (image_hash, bbox_x, bbox_y, bbox_w, bbox_h, confidence,"
                + " embedding, sub_image_jpg) VALUES ('n', 1, 2, 3, 4, 0.9, x'00', x'00')");

        SQLException failure = failing("INSERT INTO faces (image_hash, bbox_x, bbox_y, bbox_w,"
                + " bbox_h, confidence, embedding, sub_image_jpg)"
                + " VALUES ('n', 1, 2, 3, 4, 0.9, x'00', x'00')");

        assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE, resultCodeOf(failure));
        assertTrue(SqliteErrors.isDuplicateContentConflict(failure));
    }

    @Test
    void notNullViolationArrivesAsNotNullConstraint() throws Exception {
        execute("INSERT INTO images (hash) VALUES ('n')");

        SQLException failure = failing("INSERT INTO image_paths (hash, path) VALUES ('n', NULL)");

        assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL, resultCodeOf(failure));
        assertFalse(SqliteErrors.isDuplicateContentConflict(failure));
    }

    @Test
    void foreignKeyViolationArrivesAsForeignKeyConstraint() {
        SQLException failure = failing("INSERT INTO image_paths (hash, path) VALUES ('gone', '/x.jpg')");

        assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY, resultCodeOf(failure));
        assertFalse(SqliteErrors.isDuplicateContentConflict(failure));
    }

    @Test
    void triggerAbortArrivesAsTriggerConstraint() throws Exception {
        execute("CREATE TRIGGER refuse_image BEFORE INSERT ON images BEGIN "
                + "SELECT RAISE(ABORT, 'write refused'); END");

        SQLException failure = failing("INSERT INTO images (hash) VALUES ('t')");

        assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER, resultCodeOf(failure));
        assertFalse(SqliteErrors.isDuplicateContentConflict(failure));
    }

    @Test
    void malformedStatementArrivesAsPlainSqlError() {
        SQLException failure = failing("SELECT * FROM no_such_table");

        assertEquals(SQLiteErrorCode.SQLITE_ERROR, resultCodeOf(failure));
        assertFalse(SqliteErrors.isDuplicateContentConflict(failure));
    }

    /**
     * Documents why the JDBC-standard discrimination cannot work with this
     * driver, and why {@link SQLException#getErrorCode()} cannot be used
     * instead: every constraint class truncates to {@code 19}
     * ({@code SQLITE_CONSTRAINT}) and the SQL state is always {@code null}.
     */
    @Test
    void errorCodeAndSqlStateCannotTellConstraintClassesApart() throws Exception {
        execute("INSERT INTO images (hash) VALUES ('h')");
        execute("INSERT INTO images (hash) VALUES ('n')");

        SQLException duplicate = failing("INSERT INTO images (hash) VALUES ('h')");
        SQLException notNull = failing("INSERT INTO image_paths (hash, path) VALUES ('n', NULL)");

        assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY.code & 0xff, duplicate.getErrorCode());
        assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL.code & 0xff, notNull.getErrorCode());
        assertEquals(duplicate.getErrorCode(), notNull.getErrorCode(),
                "the truncated error code is identical for both constraint classes");
        assertNull(duplicate.getSQLState());
        assertNull(notNull.getSQLState());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static SQLiteException sqliteException(SQLiteErrorCode code) {
        return new SQLiteException("synthetic " + code, code);
    }

    private static SQLiteErrorCode resultCodeOf(SQLException failure) {
        assertTrue(failure instanceof SQLiteException,
                "sqlite-jdbc must report failures as SQLiteException, was " + failure.getClass());
        return ((SQLiteException) failure).getResultCode();
    }

    private void execute(String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    /** Runs a statement that must fail and returns the failure it produced. */
    private SQLException failing(String sql) {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            return e;
        }
        throw new AssertionError("expected the statement to fail: " + sql);
    }
}