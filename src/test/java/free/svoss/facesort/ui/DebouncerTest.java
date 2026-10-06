package free.svoss.facesort.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Debouncer}, the headless seam behind the View tab's
 * debounced filter field. The scheduler records what was scheduled instead of
 * timing it, so no JavaFX toolkit is needed.
 */
class DebouncerTest {

    /**
     * Scheduler that records scheduled runs; {@link #elapse()} fires them the
     * way the FX pause transition would once the delay has passed.
     */
    private static final class RecordingScheduler implements Debouncer.Scheduler {

        private final List<Runnable> pending = new ArrayList<>();
        private final List<Long> delays = new ArrayList<>();
        private int cancelCount;

        @Override
        public void schedule(Runnable action, long delayMs) {
            pending.add(action);
            delays.add(delayMs);
        }

        @Override
        public void cancel() {
            cancelCount++;
            pending.clear();
        }

        private void elapse() {
            List<Runnable> due = new ArrayList<>(pending);
            pending.clear();
            due.forEach(Runnable::run);
        }
    }

    private final RecordingScheduler scheduler = new RecordingScheduler();
    private int runs;

    private Debouncer debouncer(long delayMs) {
        return new Debouncer(delayMs, scheduler, () -> runs++);
    }

    @Test
    void triggerDefersTheActionInsteadOfRunningItNow() {
        debouncer(300).trigger();
        assertEquals(0, runs, "a keystroke must not rebuild the grid synchronously");

        scheduler.elapse();
        assertEquals(1, runs, "the action must run once the delay has elapsed");
    }

    @Test
    void triggerSchedulesTheConfiguredDelay() {
        debouncer(300).trigger();
        assertEquals(List.of(300L), scheduler.delays);
    }

    @Test
    void repeatedTriggersCoalesceIntoASingleRun() {
        Debouncer debouncer = debouncer(300);
        for (int i = 0; i < 5; i++) {
            debouncer.trigger();
        }
        assertEquals(1, scheduler.pending.size(), "each trigger must replace the pending run");

        scheduler.elapse();
        assertEquals(1, runs, "five keystrokes must cause exactly one rebuild");
    }

    @Test
    void cancelDropsThePendingRun() {
        Debouncer debouncer = debouncer(300);
        debouncer.trigger();

        debouncer.cancel();
        scheduler.elapse();

        assertTrue(scheduler.pending.isEmpty(), "the cancelled run must not be left behind");
        assertEquals(0, runs, "a cancelled trigger must never run");
    }

    @Test
    void triggerAfterCancelSchedulesAgain() {
        Debouncer debouncer = debouncer(300);
        debouncer.trigger();
        debouncer.cancel();

        debouncer.trigger();
        scheduler.elapse();

        assertEquals(1, runs);
    }

    @Test
    void cancelWithoutPendingRunIsHarmless() {
        debouncer(300).cancel();
        assertEquals(1, scheduler.cancelCount);
        assertEquals(0, runs);
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new Debouncer(300, null, () -> runs++));
        assertThrows(NullPointerException.class, () -> new Debouncer(300, scheduler, null));
    }

    @Test
    void negativeDelayIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> debouncer(-1));
    }
}
