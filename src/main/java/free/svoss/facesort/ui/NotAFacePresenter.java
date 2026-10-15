package free.svoss.facesort.ui;

/**
 * The view state a finished "not a face" marking updates.
 *
 * <p>Implemented by the two tagging views ({@link RandomNameView} and
 * {@link FaceNameView}). The success handler's body lives in a package-private
 * static method that takes one of these, so the requirement that the dismissed
 * face's card disappears from the grid — which is otherwise unreachable without
 * a JavaFX toolkit — can be pinned from a headless test.</p>
 */
interface NotAFacePresenter {

    /**
     * Removes the card of the dismissed face from the view's face grid and
     * re-evaluates any selection-dependent controls.
     *
     * @param faceId id of the face that was dismissed
     */
    void removeFace(long faceId);

    /**
     * @param message the text to show in the status label
     */
    void showStatus(String message);
}
