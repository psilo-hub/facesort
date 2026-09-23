package free.svoss.facesort.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TransactionRunner}: write units commit atomically and are
 * rolled back as a whole when the unit fails, so a multi-statement write can
 * never leave partial state behind.
 */
class TransactionRunnerTest {

    private Database db;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private int countNames(String name) throws SQLException {
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM names WHERE name = '" + name + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void insertName(String name) throws SQLException {
        try (Statement stmt = db.getConnection().createStatement()) {
            stmt.execute("INSERT INTO names (name) VALUES ('" + name + "')");
        }
    }

    @Test
    void commitsWritesAsOneUnit() throws Exception {
        db.getTransactionRunner().inTransaction(() -> {
            insertName("tx-committed-a");
            insertName("tx-committed-b");
            return null;
        });

        assertEquals(1, countNames("tx-committed-a"), "the first write of the unit must be visible");
        assertEquals(1, countNames("tx-committed-b"), "the second write of the unit must be visible");
    }

    @Test
    void rollsBackAllWritesWhenUnitThrowsSqlException() throws Exception {
        SQLException failure = new SQLException("boom");
        SQLException thrown = assertThrows(SQLException.class,
                () -> db.getTransactionRunner().inTransaction(() -> {
                    insertName("tx-rolled-back");
                    throw failure;
                }));

        assertEquals(failure, thrown, "the unit's own failure must propagate");
        assertEquals(0, countNames("tx-rolled-back"), "no partial write may survive a rollback");
    }

    @Test
    void rollsBackAllWritesWhenUnitThrowsRuntimeException() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> db.getTransactionRunner().inTransaction(() -> {
                    insertName("tx-runtime");
                    throw new IllegalStateException("runtime boomb");
                }));

        assertEquals(0, countNames("tx-runtime"), "a runtime failure must roll the unit back");
    }

    @Test
    void restoresAutoCommitAfterTheUnit() throws Exception {
        db.getTransactionRunner().inTransaction(() -> null);

        assertTrue(db.getConnection().getAutoCommit(),
                "the connection must return to its per-statement commit mode");

        insertName("auto-commit-ok");
        assertEquals(1, countNames("auto-commit-ok"),
                "a write after the unit is committed immediately");
    }

    @Test
    void holdsTheMonitorForTheWholeUnit() throws Exception {
        CountDownLatch unitStarted = new CountDownLatch(1);
        AtomicBoolean secondCallEntered = new AtomicBoolean();
        AtomicBoolean secondCallDone = new AtomicBoolean();

        Thread first = new Thread(() -> {
            try {
                db.getTransactionRunner().inTransaction(() -> {
                    insertName("tx-holds-lock");
                    unitStarted.countDown();
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
        first.start();
        assertTrue(unitStarted.await(5, TimeUnit.SECONDS), "the unit must start");

        Thread second = new Thread(() -> {
            try {
                insertName("tx-waits");
                secondCallEntered.set(true);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } finally {
                secondCallDone.set(true);
            }
        });
        second.start();
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertFalse(secondCallEntered.get(),
                "a call issued while a transaction is open must wait for its commit");

        first.join();
        second.join(5000);
        assertTrue(secondCallDone.get(), "the waiting call must complete after the commit");
        assertEquals(1, countNames("tx-waits"));
    }
}