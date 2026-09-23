package free.svoss.facesort.db;

import java.sql.SQLException;

/**
 * A unit of database work executed as a single transaction by
 * {@link TransactionRunner}. All statements issued inside {@link #run()} either
 * commit together or are rolled back together, so a multi-statement write can
 * never leave partial state behind.
 *
 * @param <T> return type of the unit
 */
@FunctionalInterface
public interface TransactionUnit<T> {

    /**
     * Executes the transactional work. Any {@link SQLException} or unchecked
     * exception aborts the whole unit and rolls all its writes back.
     *
     * @return an arbitrary result, or {@code null} when no result is needed
     * @throws SQLException if the database work fails
     */
    T run() throws SQLException;
}