package free.svoss.facesort.ui;

/**
 * The view state a finished "tag with a different name" updates.
 *
 * <p>Implemented by {@link FaceNameView}. The success handler's body lives in a
 * package-private static method that takes one of these, so the requirement
 * that the name list is reloaded — the entered name may be new and must appear
 * in the list on the left, not merely refresh the active name's candidates —
 * can be pinned from a headless test without a JavaFX toolkit.</p>
 */
interface TaggedFacePresenter {

    /**
     * @param message the text to show in the status label
     */
    void showStatus(String message);

    /**
     * Reloads the list of names while keeping the currently selected name
     * selected, so a freshly created name becomes visible.
     */
    void reloadNameList();
}
