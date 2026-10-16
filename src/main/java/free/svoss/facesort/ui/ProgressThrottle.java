package free.svoss.facesort.ui;

import free.svoss.facesort.service.ImportService;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Forwards import progress to a delegate at most once per interval, so a fast
 * import does not flood the delegate.
 *
 * <p>The first message is forwarded at once. While messages arrive inside the
 * interval the newest one replaces the one before it. A message arriving after
 * the interval is forwarded and discards whatever was held back, and {@link #flush()}
 * forwards the newest held message, so the delegate always ends up with the most
 * recent progress. Callbacks may arrive from several import workers at once; this
 * class serializes them on its own monitor and never locks the delegate.</p>
 */
final class ProgressThrottle implements ImportService.ProgressListener {

    private final ImportService.ProgressListener delegate;
    private final long interval;
    private final LongSupplier clock;

    private boolean forwarded;
    private long lastForwarded;
    private String held;

    /**
     * @param delegate the listener to forward to
     * @param interval the shortest gap between two forwarded messages, in nanoseconds
     * @param clock    the time source, in nanoseconds
     */
    ProgressThrottle(ImportService.ProgressListener delegate, long interval, LongSupplier clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.interval = interval;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized void onProgress(String message) {
        long now = clock.getAsLong();
        if (!forwarded || now - lastForwarded >= interval) {
            forwarded = true;
            lastForwarded = now;
            held = null;
            delegate.onProgress(message);
        } else {
            held = message;
        }
    }

    /**
     * Forwards the most recent held-back message, if there is one, so the last
     * progress of a run is never lost. Call once the run has finished.
     */
    synchronized void flush() {
        if (held != null) {
            String message = held;
            held = null;
            delegate.onProgress(message);
        }
    }
}
