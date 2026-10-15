package free.svoss.facesort.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps at most one cancellable action per key, so starting an action cancels
 * only the previous action of the same kind while unrelated actions keep
 * running. {@link TaskRunner} uses one instance per view and keys the actions by
 * their thread name.
 *
 * <p>The class is free of JavaFX, so the cancellation policy can be tested
 * without starting the toolkit.</p>
 *
 * <p>Not thread-safe: like the view state it belongs to, it is touched from the
 * JavaFX application thread only.</p>
 */
final class CancellationSlots {

    private final Map<String, Runnable> cancels = new HashMap<>();

    /**
     * Registers a cancellable action under the given key, cancelling the action
     * previously registered under the same key, if any.
     *
     * @param key    identifies the kind of work
     * @param cancel cancels the action
     */
    void replace(String key, Runnable cancel) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(cancel, "cancel");
        cancelKey(key);
        cancels.put(key, cancel);
    }

    /**
     * Cancels the action registered under the given key, if any.
     *
     * @param key identifies the kind of work
     */
    private void cancelKey(String key) {
        Runnable cancel = cancels.remove(key);
        if (cancel != null) {
            cancel.run();
        }
    }

    /**
     * Cancels every registered action and forgets them.
     */
    void cancelAll() {
        List<Runnable> cancelsToRun = List.copyOf(cancels.values());
        cancels.clear();
        cancelsToRun.forEach(Runnable::run);
    }
}
