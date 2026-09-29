package free.svoss.facesort.db;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Connection} proxy that records the SQL of every statement created
 * or executed through it, so a test can assert what a code path asks the
 * database for — and how many times — instead of only what it returns.
 *
 * <p>SQL is captured at both layers a query can carry it: when a statement is
 * prepared ({@code prepareStatement} / {@code createStatement}) and when a
 * statement is executed with inline SQL ({@code executeQuery(String)} and
 * friends), so the DAO style does not matter.</p>
 *
 * <p>Statements are re-proxied under the interface the caller expects
 * ({@link PreparedStatement} for {@code prepareStatement}, {@link Statement}
 * for {@code createStatement}) so the proxy stays assignable to the declared
 * return type.</p>
 */
public final class QueryCountingConnection implements InvocationHandler {

    private final Connection delegate;
    private final List<String> statements = new ArrayList<>();

    private QueryCountingConnection(Connection delegate) {
        this.delegate = delegate;
    }

    /**
     * Wraps the given connection so queries through the returned view are
     * recorded.
     *
     * @param delegate the real connection to record from
     * @return the counter, whose {@link #connection()} is the recording view
     */
    public static QueryCountingConnection around(Connection delegate) {
        return new QueryCountingConnection(delegate);
    }

    /**
     * Returns the recording view of the wrapped connection.
     */
    public Connection connection() {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, this);
    }

    /**
     * Returns how many recorded statements whose SQL contains the fragment.
     *
     * @param sqlFragment a substring of the SQL to count, e.g. a table name
     * @return the number of matching statements, in call order
     */
    public long queriesMatching(String sqlFragment) {
        return statements.stream().filter(sql -> sql.contains(sqlFragment)).count();
    }

    /**
     * Returns every recorded statement's SQL, in call order.
     */
    public List<String> statements() {
        return List.copyOf(statements);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getName().equals("prepareStatement")) {
            statements.add((String) args[0]);
            return recordingStatement((Statement) call(method, delegate, args), PreparedStatement.class);
        }
        if (method.getName().equals("createStatement")) {
            if (args != null && args.length > 0 && args[0] instanceof String sql) {
                statements.add(sql);
            }
            return recordingStatement((Statement) call(method, delegate, args), Statement.class);
        }
        return call(method, delegate, args);
    }

    private Object recordingStatement(Statement statement, Class<?> api) {
        return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("execute")
                            && args != null && args.length > 0 && args[0] instanceof String sql) {
                        statements.add(sql);
                    }
                    return call(method, statement, args);
                });
    }

    /** Invokes reflectively, unwrapping so callers see the real exception. */
    private static Object call(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
