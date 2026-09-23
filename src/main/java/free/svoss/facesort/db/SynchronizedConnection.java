package free.svoss.facesort.db;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * A {@link Connection} proxy that serializes every method call behind a single
 * monitor. The app shares one SQLite connection across all DAOs and runs DAO
 * access from many worker threads, which is unsafe for SQLite's non-thread-safe
 * connection. Wrapping the connection in this proxy makes the locking model
 * uniform: every DAO call — from any service or worker thread — is guarded by
 * the same lock, so readers, writers and workers can not interleave.
 *
 * <p>The delegate is created in {@link Database} and handed to all DAOs, so the
 * proxy is exercised by the production code and the test suite alike.</p>
 *
 * <p>{@link #inTransaction(TransactionUnit)} runs a whole unit of work behind
 * the monitor and inside a database transaction: because the monitor is held
 * for the entire unit, another worker can never slip a statement into an open
 * transaction, and the unit commits or rolls back atomically.</p>
 */
public final class SynchronizedConnection implements InvocationHandler {

    private final Connection delegate;
    private final Object monitor = new Object();

    /**
     * Creates a synchronizing handler around {@code delegate}.
     */
    SynchronizedConnection(Connection delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Returns a thread-safe, synchronizing view of {@code delegate}. All
     * interface method invocations on the returned proxy are guarded by a
     * single {@code synchronized} block.
     *
     * @param delegate the connection to serialize access to
     * @return a proxy that delegates every call to {@code delegate} under one monitor
     */
    public static Connection wrap(Connection delegate) {
        return new SynchronizedConnection(delegate).proxy();
    }

    /**
     * Returns the {@link Connection} proxy backed by this handler.
     */
    Connection proxy() {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        synchronized (monitor) {
            return method.invoke(delegate, args);
        }
    }

    /**
     * Runs the given unit as one atomic transaction behind the monitor. On
     * success the unit is committed; on {@link SQLException} or an unchecked
     * exception every write done so far is rolled back and the original failure
     * rethrown. The connection's original auto-commit mode is restored
     * afterwards.
     *
     * @param unit the work to perform; must not be null
     * @param <T>  return type of the unit
     * @return the result returned by the unit
     * @throws SQLException if the unit fails or the transaction machinery fails
     */
    public <T> T inTransaction(TransactionUnit<T> unit) throws SQLException {
        Objects.requireNonNull(unit, "unit");
        synchronized (monitor) {
            boolean previousAutoCommit = delegate.getAutoCommit();
            delegate.setAutoCommit(false);
            try {
                T result = unit.run();
                delegate.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    delegate.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                try {
                    delegate.setAutoCommit(previousAutoCommit);
                } catch (SQLException e) {
                    // Best effort only: the previous mode is left as-is.
                }
            }
        }
    }
}