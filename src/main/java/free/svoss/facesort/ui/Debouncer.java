package free.svoss.facesort.ui;

import java.util.Objects;

/**
 * Coalesces a burst of events (such as keystrokes in a filter field) into a
 * single run of an action: every {@link #trigger()} pushes the run back to
 * after the configured delay, so the action fires once the burst goes quiet.
 *
 * <p>Timing is delegated to a {@link Scheduler}, which keeps this class free of
 * JavaFX types and therefore testable without a toolkit. The view wires it to
 * a {@code PauseTransition}.</p>
 *
 * <p>Instances are not thread-safe; they are meant to be triggered and to run
 * on the UI thread.</p>
 */
final class Debouncer {

    /**
     * Defers the run of an action. The debouncer drops any run still pending
     * before scheduling the next one, so a burst of triggers ends in a single
     * run.
     */
    interface Scheduler {

        /**
         * Schedules {@code action} to run after {@code delayMs}.
         *
         * @param action  the action to run
         * @param delayMs the delay in milliseconds
         */
        void schedule(Runnable action, long delayMs);

        /**
         * Drops the pending run, if there is one. Must be safe to call when
         * nothing is pending.
         */
        void cancel();
    }

    private final long delayMs;
    private final Scheduler scheduler;
    private final Runnable action;

    /**
     * Creates a debouncer.
     *
     * @param delayMs   the quiet period after the last trigger, in milliseconds
     * @param scheduler the timing backend
     * @param action    the action to run once the burst goes quiet
     */
    Debouncer(long delayMs, Scheduler scheduler, Runnable action) {
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must not be negative: " + delayMs);
        }
        this.delayMs = delayMs;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.action = Objects.requireNonNull(action, "action");
    }

    /**
     * Requests the action to run, pushing a run that is still pending further
     * into the future.
     */
    void trigger() {
        scheduler.cancel();
        scheduler.schedule(action, delayMs);
    }

    /**
     * Drops a pending run, if there is one. Call this when the action has been
     * performed out of band, so a stale trigger does not run it a second time.
     */
    void cancel() {
        scheduler.cancel();
    }
}
