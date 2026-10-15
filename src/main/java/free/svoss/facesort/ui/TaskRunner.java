package free.svoss.facesort.ui;

import java.util.Objects;
import javafx.concurrent.Task;

/**
 * Runs the background {@link Task}s of a UI view on daemon threads.
 *
 * <p>A view holds one {@code TaskRunner} instance and starts every task through
 * {@link #start(Task, String)}. The thread name identifies the kind of work —
 * {@code "facename-name-loader"}, {@code "dedupe-decision"} and so on — and is
 * also the key the runner cancels by: starting a task cancels the previously
 * started task of the same kind only, so stale work never stacks up while
 * unrelated actions (reloading a list and exporting a name, say) no longer stop
 * each other.</p>
 *
 * <p>{@link #start} returns the task it was given so that success and failure
 * handlers can be attached to the local instance and read that instance's
 * value. Binding handlers to the started task (instead of reading a shared
 * "current task" field) is what keeps a handler from picking up a newer task's
 * result when an older task finishes after a newer one has already been
 * started.</p>
 */
public final class TaskRunner {

    private final CancellationSlots slots = new CancellationSlots();

    /**
     * Cancels the previously started task of the same kind, if any, and starts
     * the given task on a new daemon thread. Handlers subscribed on the returned
     * instance are bound to exactly this task, so they always observe its
     * result. Tasks started under different names run concurrently.
     *
     * @param task       the task to run
     * @param threadName the name of the daemon thread running the task; also
     *                   identifies the kind of work for cancellation
     * @param <T>        the task result type
     * @return the started task
     */
    public <T> Task<T> start(Task<T> task, String threadName) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(threadName, "threadName");
        slots.replace(threadName, () -> task.cancel(true));
        Thread thread = new Thread(task, threadName);
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    /**
     * Cancels every task this runner started, if any. Used when a view stops the
     * work behind its current state, e.g. when a session ends.
     */
    public void cancelAll() {
        slots.cancelAll();
    }
}
