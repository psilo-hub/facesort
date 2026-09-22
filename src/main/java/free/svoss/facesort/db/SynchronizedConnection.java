package free.svoss.facesort.db;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
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
 * proxy is exercised by the production code and the test suite alike.
 */
public final class SynchronizedConnection implements InvocationHandler {

    private final Connection delegate;
    private final Object monitor = new Object();

    private SynchronizedConnection(Connection delegate) {
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
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new SynchronizedConnection(delegate));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        synchronized (monitor) {
            return method.invoke(delegate, args);
        }
    }
}