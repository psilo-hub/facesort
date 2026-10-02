package free.svoss.facesort.db;

import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.sql.SQLException;

/**
 * Recognition of the SQLite failures the DAOs and services have to tell apart.
 *
 * <p>sqlite-jdbc reports every database failure as an
 * {@link SQLiteException}, and that class has a single constructor which calls
 * {@code super(message, null, code & 0xff)}. So the JDBC-standard
 * {@link java.sql.SQLIntegrityConstraintViolationException} and
 * {@link java.sql.SQLTransactionRollbackException} are never thrown by this
 * driver, {@link SQLException#getSQLState()} is always {@code null}, and
 * {@link SQLException#getErrorCode()} is the extended code truncated to its low
 * byte — {@code 19} ({@code SQLITE_CONSTRAINT}) for every constraint class,
 * which is therefore useless for telling them apart. The only thing that
 * survives is the extended result code on the driver exception itself, which is
 * what {@link #isDuplicateContentConflict(SQLException)} reads.</p>
 */
public final class SqliteErrors {

    private SqliteErrors() {
    }

    /**
     * Reports whether the given failure is a duplicate-content conflict: the
     * row already exists under the same primary key or the same unique index.
     * This is the only shape that means "somebody else stored this first", so
     * it is the only one a caller may treat as an already-done write.
     *
     * <p>Both codes are needed. A duplicate on a declared key arrives as
     * {@link SQLiteErrorCode#SQLITE_CONSTRAINT_PRIMARYKEY} (SQLite reports the
     * constraint that was declared), which is what a second insert into
     * {@code images.hash} or {@code image_paths} raises. A duplicate on a
     * separate unique index arrives as
     * {@link SQLiteErrorCode#SQLITE_CONSTRAINT_UNIQUE}, which is what the
     * {@code faces} index over
     * {@code (image_hash, bbox_x, bbox_y, bbox_w, bbox_h)} raises.</p>
     *
     * <p>Everything else is a real failure and must propagate: NOT NULL, FOREIGN
     * KEY, CHECK, a {@code RAISE(...)} in a trigger, {@code SQLITE_ERROR},
     * {@code SQLITE_FULL}, I/O and corruption errors — and any exception from
     * another JDBC driver, which is not a
     * {@link SQLiteException} at all.</p>
     *
     * @param e the failure to classify; a {@code null} or any non-driver
     *           {@link SQLException} is not a conflict
     * @return {@code true} only for a duplicate primary key / unique index
     */
    public static boolean isDuplicateContentConflict(SQLException e) {
        if (!(e instanceof SQLiteException)) {
            return false;
        }
        SQLiteErrorCode code = ((SQLiteException) e).getResultCode();
        return code == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY
                || code == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE;
    }
}