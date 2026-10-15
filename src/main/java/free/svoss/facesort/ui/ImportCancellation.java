package free.svoss.facesort.ui;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The cooperative cancellation state of one import run.
 *
 * <p>The flag is written on the JavaFX application thread — by the Stop button
 * and by {@link ImportView#dispose()} when the window is rebuilt after a
 * language change — while the background run polls it and stops at its next
 * checkpoint between files. It therefore has to be visible across threads,
 * which is what the {@link AtomicBoolean} provides.</p>
 */
final class ImportCancellation {

    private final AtomicBoolean requested = new AtomicBoolean();

    /**
     * Requests cancellation; the running import stops at its next checkpoint.
     */
    void request() {
        requested.set(true);
    }

    /**
     * Clears a previous request before a new import run starts.
     */
    void reset() {
        requested.set(false);
    }

    /**
     * @return whether the running import has been asked to stop
     */
    boolean isRequested() {
        return requested.get();
    }
}
