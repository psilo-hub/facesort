package free.svoss.facesort.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure range-selection logic of {@link FaceNameView}.
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
}