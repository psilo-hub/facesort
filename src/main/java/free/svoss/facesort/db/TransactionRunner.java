package free.svoss.facesort.db;

import java.sql.SQLException;
import java.util.Objects;

/**
 * Executes {@link TransactionUnit units of database work} against the shared
 * connection as single transactions.
 *
 * <p>Every DAO call is serialized behind the same monitor in
 * {@link SynchronizedConnection}. {@link #inTransaction(TransactionUnit)} holds
 * that monitor for the whole unit, so an open transaction never absorbs
 * statements from other worker threads: while the unit runs, no other
 * connection access happens at all, and the whole unit is committed or rolled
 * back atomically.</p>
 */
public final class TransactionRunner {

    private final SynchronizedConnection connection;

    /**
     * @param connection the synchronized connection to run transactions on
     */
    public TransactionRunner(SynchronizedConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Runs the given unit as one atomic transaction. On success the unit is
     * committed; on {@link SQLException} or an unchecked exception every write
     * done so far is rolled back and the original failure rethrown.
     *
     * @param unit the work to perform; must not be null
     * @param <T>  return type of the unit
     * @return the result returned by the unit
     * @throws SQLException if the unit fails
     */
    public <T> T inTransaction(TransactionUnit<T> unit) throws SQLException {
        return connection.inTransaction(unit);
    }
}