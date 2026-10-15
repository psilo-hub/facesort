package free.svoss.facesort.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CancellationSlots}, the headless seam behind the cancellation
 * policy of {@link TaskRunner}. It keeps one cancellable action per kind of work,
 * so starting an action cancels only the previous action of the same kind.
 */
class CancellationSlotsTest {

    private final CancellationSlots slots = new CancellationSlots();
    private final List<String> cancelled = new ArrayList<>();

    @Test
    void replace_sameKey_cancelsThePreviousAction() {
        slots.replace("loader", () -> cancelled.add("first"));
        slots.replace("loader", () -> cancelled.add("second"));

        assertEquals(List.of("first"), cancelled, "stale work of the same kind must be cancelled");
    }

    @Test
    void replace_differentKey_keepsUnrelatedActions() {
        slots.replace("loader", () -> cancelled.add("loader"));
        slots.replace("exporter", () -> cancelled.add("exporter"));

        assertTrue(cancelled.isEmpty(), "unrelated actions must not be cancelled, but were: " + cancelled);
    }

    @Test
    void cancelAll_cancelsEveryAction() {
        slots.replace("loader", () -> cancelled.add("loader"));
        slots.replace("exporter", () -> cancelled.add("exporter"));
        cancelled.clear();

        slots.cancelAll();

        assertEquals(2, cancelled.size(), "both live actions must be cancelled, got: " + cancelled);
        assertTrue(cancelled.containsAll(List.of("loader", "exporter")));
    }

    @Test
    void cancelAll_forgetsTheActions() {
        slots.replace("loader", () -> cancelled.add("loader"));
        slots.cancelAll();
        cancelled.clear();

        slots.cancelAll();

        assertTrue(cancelled.isEmpty(), "an already cancelled action must not be cancelled again");
    }

    @Test
    void replace_afterCancellation_cancelsOnlyTheNewAction() {
        slots.replace("loader", () -> cancelled.add("first"));
        slots.replace("loader", () -> cancelled.add("second"));
        cancelled.clear();

        slots.cancelAll();

        assertEquals(List.of("second"), cancelled, "only the live action may still be cancelled");
    }
}
