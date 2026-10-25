package free.svoss.facesort.ui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import javafx.application.Platform;
import javafx.concurrent.Task;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless tests for {@link TaskRunner}, the daemon-thread runner used by UI
 * views to run background tasks. No JavaFX controls are built here; the JavaFX
 * toolkit is initialized only so that a {@link Task} can complete (its state
 * changes are marshalled through {@code Platform.runLater}).
 * <p>
 * Task outcomes are observed through {@link Task#get(long, java.util.concurrent.TimeUnit)}
 * (thread-safe) rather than {@link Task#getState()}, which requires the FX
 * application thread.
 */
class TaskRunnerTest {

    private static final long TIMEOUT_SECONDS = 10;

    @BeforeAll
    static void initToolkit() {
        Platform.startup(() -> {
        });
    }

    @AfterAll
    static void stopToolkit() {
        Platform.exit();
    }

    private final TaskRunner runner = new TaskRunner();

    @Test
    void start_runsTaskOnNamedDaemonThreadAndReturnsIt() throws Exception {
        final String[] threadName = new String[1];
        final boolean[] isDaemon = new boolean[1];
        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                threadName[0] = Thread.currentThread().getName();
                isDaemon[0] = Thread.currentThread().isDaemon();
                return "done";
            }
        };
        Task<String> returned = runner.start(task, "loader-1");

        assertSame(task, returned, "start must return the task it was given");
        assertEquals("done", task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("loader-1", threadName[0]);
        assertTrue(isDaemon[0], "the task must run on a daemon thread");
    }

    @Test
    void start_taskFailure_propagatesThroughReturnedTask() throws Exception {
        RuntimeException boom = new RuntimeException("boom");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                throw boom;
            }
        };
        runner.start(task, "failing");

        ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "reading the result of a failed task must propagate the failure");
        assertSame(boom, thrown.getCause(), "the original failure must reach the caller");
    }

    @Test
    void start_sameKeyCancelsPreviousTask() throws Exception {
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        final boolean[] firstWasCancelled = new boolean[1];
        Task<Void> first = new Task<>() {
            @Override
            protected Void call() {
                firstRunning.countDown();
                try {
                    releaseFirst.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                firstWasCancelled[0] = isCancelled();
                return null;
            }
        };
        runner.start(first, "same-key");
        assertTrue(firstRunning.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "the first task must start running");

        // Starting a new task with the same key cancels the previous one.
        Task<Void> second = new Task<>() {
            @Override
            protected Void call() {
                return null;
            }
        };
        runner.start(second, "same-key");

        releaseFirst.countDown();
        second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(firstWasCancelled[0] || first.isCancelled(),
            "the previous task of the same key must be cancelled when a new one starts");
    }

    @Test
    void cancelAll_cancelsRunningTasks() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        final boolean[] wasInterrupted = new boolean[1];
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                running.countDown();
                try {
                    release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    wasInterrupted[0] = true;
                    Thread.currentThread().interrupt();
                }
                return null;
            }
        };
        runner.start(task, "cancellable");
        assertTrue(running.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "the task must start running");

        runner.cancelAll();
        release.countDown();

        assertTrue(wasInterrupted[0] || task.isCancelled(),
            "cancelAll must interrupt or cancel running tasks");
    }
}
