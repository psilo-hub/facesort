package free.svoss.facesort.ui;

import free.svoss.facesort.service.DataRemovalService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the scan-result handling of {@link RemoveByPrefixDialog}.
 *
 * <p>The dialog itself needs a JavaFX toolkit, so the success-handler body is
 * extracted into {@link RemoveByPrefixDialog#applyScanResult} and driven here
 * through a recording {@link ScanPresenter}.</p>
 */
class RemoveByPrefixDialogTest {

    @Test
    void nullEstimate_leavesTheDialogUsable() {
        RecordingPresenter presenter = new RecordingPresenter();

        RemoveByPrefixDialog.applyScanResult(null, presenter);

        assertEquals(List.of("busy:false"),
                presenter.calls,
                "a null estimate must still take the dialog out of its busy state, "
                        + "otherwise the dialog stays disabled with no way back");
        assertFalse(presenter.removeEnabled,
                "with nothing counted, Remove must not be offered");
    }

    @Test
    void estimateWithoutMatches_reportsNothingToRemove() {
        RecordingPresenter presenter = new RecordingPresenter();

        RemoveByPrefixDialog.applyScanResult(new DataRemovalService.Estimate(0, 0, 0, 0), presenter);

        assertEquals(List.of("busy:false"), presenter.calls);
        assertTrue(presenter.lastMessage.contains("nothing to remove"),
                "expected the 'no match' message, got: " + presenter.lastMessage);
        assertFalse(presenter.removeEnabled, "Remove must stay disabled for an empty prefix match");
    }

    @Test
    void estimateWithMatches_offersRemoval() {
        RecordingPresenter presenter = new RecordingPresenter();

        RemoveByPrefixDialog.applyScanResult(new DataRemovalService.Estimate(3, 1, 3, 9), presenter);

        assertEquals(List.of("busy:false"), presenter.calls);
        assertTrue(presenter.lastMessage.contains("3"), "the counts must be shown, got: "
                + presenter.lastMessage);
        assertTrue(presenter.lastMessage.contains("9"), "the face count must be shown, got: "
                + presenter.lastMessage);
        assertTrue(presenter.removeEnabled, "a matching prefix must enable Remove");
    }

    /** Records what the dialog was told, in order. */
    private static final class RecordingPresenter implements ScanPresenter {

        private final List<String> calls = new ArrayList<>();
        private String lastMessage = "";
        private boolean removeEnabled;

        @Override
        public void setBusyState(boolean busy) {
            calls.add("busy:" + busy);
        }

        @Override
        public void showMessage(String message) {
            lastMessage = message;
        }

        @Override
        public void setRemoveEnabled(boolean enabled) {
            removeEnabled = enabled;
        }
    }
}
