package free.svoss.facesort;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the shutdown sequence of {@link FaceSortApp}.
 */
class FaceSortAppTest {

    private final List<String> closeOrder = new ArrayList<>();

    /**
     * Records that it was closed, in the order it happened, and optionally
     * fails the way a half-torn-down native resource would.
     */
    private final class Recorder implements AutoCloseable {

        private final String name;
        private final Throwable failure;

        Recorder(String name) {
            this(name, null);
        }

        Recorder(String name, Throwable failure) {
            this.name = name;
            this.failure = failure;
        }

        @Override
        public void close() throws Exception {
            closeOrder.add(name);
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
        }
    }

    @Test
    void everyResourceIsClosedInOrder() throws Exception {
        FaceSortApp.closeAll(new Recorder("importService"), new Recorder("faceAiService"),
                new Recorder("database"));

        assertEquals(List.of("importService", "faceAiService", "database"), closeOrder,
                "every resource must be released");
    }

    @Test
    void aFailureDoesNotStopTheRemainingResourcesFromClosing() {
        RuntimeException broken = new RuntimeException("importService.close failed");

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                FaceSortApp.closeAll(new Recorder("importService", broken),
                        new Recorder("faceAiService"), new Recorder("database")));

        assertSame(broken, thrown, "the original failure must reach the caller");
        assertEquals(List.of("importService", "faceAiService", "database"), closeOrder,
                "a failing FaceAI close must not leak the database handle");
    }

    @Test
    void laterFailuresAreAttachedToTheFirstOne() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        RuntimeException third = new RuntimeException("third");

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                FaceSortApp.closeAll(new Recorder("a", first), new Recorder("b", second),
                        new Recorder("c", third)));

        assertSame(first, thrown);
        assertEquals(List.of(second, third), List.of(thrown.getSuppressed()),
                "no failure may be swallowed");
        assertEquals(List.of("a", "b", "c"), closeOrder);
    }

    @Test
    void nullsAreSkipped() throws Exception {
        FaceSortApp.closeAll(null, new Recorder("database"), null);

        assertEquals(List.of("database"), closeOrder,
                "a startup that failed before building a service must still close what it did build");
    }

    @Test
    void nothingToCloseIsNotAnError() throws Exception {
        FaceSortApp.closeAll();

        assertTrue(closeOrder.isEmpty());
    }

    @Test
    void anErrorIsRethrownAfterTheRestAreClosed() {
        AssertionError broken = new AssertionError("native teardown failed");

        AssertionError thrown = assertThrows(AssertionError.class, () ->
                FaceSortApp.closeAll(new Recorder("importService", broken),
                        new Recorder("database")));

        assertSame(broken, thrown, "an Error must not be swallowed or wrapped");
        assertEquals(List.of("importService", "database"), closeOrder);
    }
}
