package free.svoss.facesort.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ImportCancellation}, the cooperative cancellation state of
 * the Import tab. It is shared between the JavaFX application thread — the Stop
 * button and {@link ImportView#dispose()} when a language change replaces the
 * window — and the background run, which polls it between files.
 */
class ImportCancellationTest {

    private final ImportCancellation cancellation = new ImportCancellation();

    @Test
    void newRun_isNotCancelled() {
        assertFalse(cancellation.isRequested());
    }

    @Test
    void request_isSeenByTheRunningImport() {
        cancellation.request();

        assertTrue(cancellation.isRequested());
    }

    @Test
    void reset_clearsTheRequestBeforeTheNextRun() {
        cancellation.request();

        cancellation.reset();

        assertFalse(cancellation.isRequested(), "a new run must not start already cancelled");
    }

    @Test
    void request_staysSetUntilTheRunResetsIt() {
        cancellation.request();

        assertTrue(cancellation.isRequested());
        assertTrue(cancellation.isRequested(), "the request must survive repeated polling");
    }

    @Test
    void requestFromAnotherThread_isVisibleToTheRunningImport() throws InterruptedException {
        Thread stopButton = new Thread(cancellation::request, "test-stop-button");
        stopButton.start();
        stopButton.join();

        assertTrue(cancellation.isRequested(),
                "the Stop button and dispose() run on the FX thread, the import polls from the worker");
    }
}
