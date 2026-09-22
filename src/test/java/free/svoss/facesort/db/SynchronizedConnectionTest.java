package free.svoss.facesort.db;

import free.svoss.facesort.model.ImageRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SynchronizedConnection}.
 */
class SynchronizedConnectionTest {

    private Database db;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    @Test
    void wrapReturnsAConnectionProxy() {
        Connection wrapped = SynchronizedConnection.wrap(db.getConnection());

        assertNotNull(wrapped);
        assertTrue(Proxy.isProxyClass(wrapped.getClass()));
        assertTrue(wrapped instanceof Connection);
    }

    @Test
    void wrappedConnectionDelegatesStatementsToTheRealConnection() throws Exception {
        Connection wrapped = SynchronizedConnection.wrap(db.getConnection());

        try (Statement stmt = wrapped.createStatement()) {
            stmt.execute("CREATE TABLE t (id INTEGER)");
            stmt.execute("INSERT INTO t (id) VALUES (42)");
        }
        try (Statement stmt = wrapped.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id FROM t")) {
            assertTrue(rs.next());
            assertEquals(42, rs.getInt(1));
        }
    }

    @Test
    void wrappedConnectionWorksWithTheProductionDaos() throws Exception {
        Connection wrapped = SynchronizedConnection.wrap(db.getConnection());
        ImageDao dao = new ImageDao(wrapped);

        dao.insert("hash-wrapped", 123L, "{}", 1);

        ImageRecord record = dao.findByHash("hash-wrapped").orElseThrow();
        assertEquals("hash-wrapped", record.hash());
        assertEquals(123L, record.detectionTs());
    }

    @Test
    void wrapSerializesConcurrentCalls() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        Connection delegate = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("createStatement")) {
                        int now = inside.incrementAndGet();
                        maxInside.accumulateAndGet(now, Math::max);
                        Thread.sleep(20);
                        inside.decrementAndGet();
                        return null;
                    }
                    throw new AssertionError("unexpected call: " + method);
                });

        Connection wrapped = SynchronizedConnection.wrap(delegate);
        int threadCount = 8;
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            threads.add(new Thread(() -> {
                for (int j = 0; j < 50; j++) {
                    try {
                        wrapped.createStatement();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(1, maxInside.get(), "only one call may be inside at a time");
    }
}