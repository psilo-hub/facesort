package free.svoss.facesort.ui;

import javafx.scene.Node;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the toolkit-free logic of {@link FaceUi}.
 *
 * <p>The view helpers themselves need a JavaFX toolkit, so the shared "not a
 * face" success handler is a package-private static method driven through an
 * injected presenter, and the shared card selection works on plain nodes that
 * can be built without a running toolkit.</p>
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

    @Test
    void rangeSelectionInDisplayOrderForwardDirection() {
        assertEquals(List.of(2, 3, 4), FaceUi.rangeSelection(2, 4, 6));
    }

    @Test
    void rangeSelectionNormalizesBackwardDirection() {
        assertEquals(List.of(2, 3, 4), FaceUi.rangeSelection(4, 2, 6));
    }

    @Test
    void rangeSelectionOfSingleCard() {
        assertEquals(List.of(3), FaceUi.rangeSelection(3, 3, 6));
    }

    @Test
    void rangeSelectionOfEmptyPane() {
        assertTrue(FaceUi.rangeSelection(0, 2, 0).isEmpty());
    }

    @Test
    void rangeSelectionClampsIndicesToPane() {
        assertEquals(List.of(0, 1), FaceUi.rangeSelection(-1, 1, 3));
        assertEquals(List.of(3, 4), FaceUi.rangeSelection(3, 99, 5));
    }

    @Test
    void plainClickTogglesTheCardAndBecomesTheAnchor() {
        List<VBox> cards = cards(3);

        Node anchor = FaceUi.selectOnClick(cards, cards.get(1), false, null);

        assertEquals(List.of(1), selected(cards), "a plain click toggles the clicked card");
        assertEquals(cards.get(1), anchor, "the clicked card fixes the start of a shift range");
    }

    @Test
    void shiftClickSelectsTheWholeRange() {
        List<VBox> cards = cards(5);
        FaceUi.selectOnClick(cards, cards.get(1), false, null);

        FaceUi.selectOnClick(cards, cards.get(3), true, cards.get(1));

        assertEquals(List.of(1, 2, 3), selected(cards),
                "shift-clicking must select every card between the anchor and the clicked one");
    }

    @Test
    void shiftClickReplacesThePreviousSelection() {
        List<VBox> cards = cards(5);
        FaceUi.selectOnClick(cards, cards.get(0), false, null);

        FaceUi.selectOnClick(cards, cards.get(2), true, cards.get(0));

        assertEquals(List.of(0, 1, 2), selected(cards),
                "cards outside the range must not stay selected");
    }

    @Test
    void repeatedShiftClicksKeepTheSameAnchor() {
        List<VBox> cards = cards(5);
        FaceUi.selectOnClick(cards, cards.get(1), false, null);
        Node anchor = FaceUi.selectOnClick(cards, cards.get(3), true, cards.get(1));

        FaceUi.selectOnClick(cards, cards.get(0), true, anchor);

        assertEquals(cards.get(1), anchor,
                "a shift-click must not move the anchor, so the next range still starts there");
        assertEquals(List.of(0, 1), selected(cards),
                "the second range must be measured from the original anchor");
    }

    @Test
    void shiftClickWithoutAnAnchorTogglesLikeAPlainClick() {
        List<VBox> cards = cards(3);

        Node anchor = FaceUi.selectOnClick(cards, cards.get(1), true, null);

        assertEquals(List.of(1), selected(cards));
        assertEquals(cards.get(1), anchor, "the first click always anchors the selection");
    }

    @Test
    void shiftClickAfterTheAnchorLeftTheGridTogglesLikeAPlainClick() {
        VBox staleAnchor = cards(3).get(0);
        List<VBox> reloaded = cards(3);

        FaceUi.selectOnClick(reloaded, reloaded.get(2), true, staleAnchor);

        assertEquals(List.of(2), selected(reloaded),
                "a range cannot be built from a card that is no longer shown");
    }

    /** Cards only need to carry the {@code selected} style class. */
    private static List<VBox> cards(int count) {
        List<VBox> cards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            cards.add(new VBox());
        }
        return cards;
    }

    /** The indices of the cards that currently look selected. */
    private static List<Integer> selected(List<VBox> cards) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).getStyleClass().contains("selected")) {
                indices.add(i);
            }
        }
        return indices;
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
