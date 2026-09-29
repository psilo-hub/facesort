package free.svoss.facesort.ui;

import free.svoss.facesort.model.NameRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that work bound to the selected name keeps running against the name
 * that was selected when the work was bound, even when the selection changes
 * before the work actually runs.
 */
class NameBoundWorkTest {

    private static final NameRecord ALICE = new NameRecord(1, "Alice", null);
    private static final NameRecord BOB = new NameRecord(2, "Bob", null);

    @Test
    void workRunsAgainstTheNameSelectedWhenItWasBound() throws Exception {
        AtomicReference<NameRecord> selected = new AtomicReference<>(ALICE);

        NameBoundWork.Bound<String> bound = NameBoundWork.forSelectedName(
                selected::get, name -> "untagged from " + name.name());

        selected.set(BOB); // the user picks another person while the task is queued

        assertEquals("untagged from Alice", bound.work().call());
    }

    @Test
    void boundExposesTheNameSoHandlersNeverReReadTheSelection() {
        AtomicReference<NameRecord> selected = new AtomicReference<>(ALICE);

        NameBoundWork.Bound<Long> bound = NameBoundWork.forSelectedName(
                selected::get, NameRecord::id);

        selected.set(BOB);

        assertEquals(ALICE, bound.name(),
                "a success handler must be able to name the person it acted on");
    }

    @Test
    void repeatedRunsKeepTargetingTheSameName() throws Exception {
        AtomicReference<NameRecord> selected = new AtomicReference<>(ALICE);
        NameBoundWork.Bound<Long> bound = NameBoundWork.forSelectedName(
                selected::get, NameRecord::id);

        selected.set(BOB);

        assertEquals(1L, bound.work().call());
        assertEquals(1L, bound.work().call(),
                "a retry of the same bound work must still target the original name");
    }

    @Test
    void theSelectionIsReadExactlyOnce() {
        AtomicReference<NameRecord> selected = new AtomicReference<>(ALICE);
        int[] reads = {0};

        NameBoundWork.forSelectedName(() -> {
            reads[0]++;
            return selected.get();
        }, NameRecord::id);

        assertEquals(1, reads[0], "binding the work must take a single snapshot");
    }

    @Test
    void workFailuresPropagateToTheCaller() {
        NameBoundWork.Bound<Void> bound = NameBoundWork.forSelectedName(
                () -> ALICE, name -> {
                    throw new IOException("disk full");
                });

        assertThrows(IOException.class, () -> bound.work().call(),
                "the task body must see the real failure, so the view can report it");
    }
}
