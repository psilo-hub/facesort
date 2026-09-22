package free.svoss.facesort.ui;

import javafx.concurrent.Task;

/**
 * Runs the background {@link Task}s of a UI view on daemon threads.
 *
 * <p>A view holds one {@code TaskRunner} instance. Every task is started
 * through {@link #start(Task, String)}, which first cancels the previously
 * started task, so at most one view task runs at a time and stale work never
 * stacks up. {@link #start} returns the task it was given so that success and
 * failure handlers can be attached to the local instance and read that
 * instance's value. Binding handlers to the started task (instead of reading a
 * shared "current task" field) is what keeps a handler from picking up a newer
 * task's result when an older task finishes after a newer one has already
 * been started.</p>
 */
public final class TaskRunner {

    private Task<?> activeTask;

    /**
     * Cancels the currently active task, if any, and starts the given task on
     * a new daemon thread. Handlers subscribed on the returned instance are
     * bound to exactly this task, so they always observe its result.
     *
     * @param task       the task to run
     * @param threadName the name of the daemon thread running the task
     * @param <T>        the task result type
     * @return the started task
     */
    public <T> Task<T> start(Task<T> task, String threadName) {
        if (activeTask != null) {
            activeTask.cancel(true);
        }
        activeTask = task;
        Thread thread = new Thread(task, threadName);
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    /**
     * Cancels the currently active task, if any, and forgets it.
     */
    public void cancelActive() {
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
    }
}