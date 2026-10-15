package free.svoss.facesort.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the toolkit-free logic of {@link FaceNameView}.
 *
 * <p>The view itself needs a JavaFX toolkit, so the "tag with a different
 * name" success handler is a package-private static method that can be driven
 * headlessly.</p>
 */
class FaceNameViewTest {

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
