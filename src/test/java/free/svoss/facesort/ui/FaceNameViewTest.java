package free.svoss.facesort.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the toolkit-free logic of {@link FaceNameView}.
 *
 * <p>The view itself needs a JavaFX toolkit, so the range-selection maths and
 * the "tag with a different name" success handler are extracted into
 * package-private static methods that can be driven headlessly.</p>
 */
class FaceNameViewTest {

    @Test
    void rangeSelectionInDisplayOrderForwardDirection() {
        assertEquals(List.of(2, 3, 4), FaceNameView.rangeSelection(2, 4, 6));
    }

    @Test
    void rangeSelectionNormalizesBackwardDirection() {
        assertEquals(List.of(2, 3, 4), FaceNameView.rangeSelection(4, 2, 6));
    }

    @Test
    void rangeSelectionOfSingleCard() {
        assertEquals(List.of(3), FaceNameView.rangeSelection(3, 3, 6));
    }

    @Test
    void rangeSelectionOfEmptyPane() {
        assertTrue(FaceNameView.rangeSelection(0, 2, 0).isEmpty());
    }

    @Test
    void rangeSelectionClampsIndicesToPane() {
        assertEquals(List.of(0, 1), FaceNameView.rangeSelection(-1, 1, 3));
        assertEquals(List.of(3, 4), FaceNameView.rangeSelection(3, 99, 5));
    }

    @Test
    void differentNameTag_successReloadsTheNameList() {
        RecordingPresenter presenter = new RecordingPresenter();

        FaceNameView.applyDifferentNameTagResult("Alice", presenter);

        assertEquals(List.of("status", "reloadNameList"),
                presenter.calls,
                "tagging with a name that may be new must reload the name list so the "
                        + "new name appears on the left, not only refresh the active "
                        + "name's candidates");
    }

    @Test
    void differentNameTag_successReportsTheTaggedName() {
        RecordingPresenter presenter = new RecordingPresenter();

        FaceNameView.applyDifferentNameTagResult("Alice", presenter);

        assertTrue(presenter.lastStatus.contains("Alice"),
                "the tagged name must be shown, got: " + presenter.lastStatus);
    }

    /** Records what the view was told, in order. */
    private static final class RecordingPresenter implements TaggedFacePresenter {

        private final List<String> calls = new ArrayList<>();
        private String lastStatus = "";

        @Override
        public void showStatus(String message) {
            calls.add("status");
            lastStatus = message;
        }

        @Override
        public void reloadNameList() {
            calls.add("reloadNameList");
        }
    }
}
