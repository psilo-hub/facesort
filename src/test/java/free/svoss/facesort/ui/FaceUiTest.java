package free.svoss.facesort.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for the toolkit-free logic of {@link FaceUi}.
 *
 * <p>The view helpers themselves need a JavaFX toolkit, so the shared "not a
 * face" success handler is a package-private static method driven through an
 * injected presenter.</p>
 */
class FaceUiTest {

    @Test
    void notAFace_successRemovesTheDismissedCard() {
        RecordingPresenter presenter = new RecordingPresenter();

        FaceUi.applyNotAFaceResult(7L, presenter);

        assertEquals(List.of("removeFace:7", "status"),
                presenter.calls,
                "a dismissed face must leave the grid, otherwise it stays clickable");
    }

    @Test
    void notAFace_successReportsTheOutcome() {
        RecordingPresenter presenter = new RecordingPresenter();

        FaceUi.applyNotAFaceResult(7L, presenter);

        assertFalse(presenter.lastStatus.isBlank(),
                "the outcome of dismissing a face must be shown");
    }

    /** Records what the view was told, in order. */
    private static final class RecordingPresenter implements NotAFacePresenter {

        private final List<String> calls = new ArrayList<>();
        private String lastStatus = "";

        @Override
        public void removeFace(long faceId) {
            calls.add("removeFace:" + faceId);
        }

        @Override
        public void showStatus(String message) {
            calls.add("status");
            lastStatus = message;
        }
    }
}
