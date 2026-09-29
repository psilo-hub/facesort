package free.svoss.facesort.ui;

/**
 * The dialog state a finished scan updates.
 *
 * <p>Implemented by {@link RemoveByPrefixDialog}. The success handler's body
 * lives in a package-private static method that takes one of these, so the
 * logic — including the {@code null} estimate path that used to leave the dialog
 * disabled — can be pinned from a headless test.</p>
 */
interface ScanPresenter {

    /**
     * Disables the whole dialog while a background operation runs.
     *
     * @param busy {@code true} while a background operation runs
     */
    void setBusyState(boolean busy);

    /**
     * @param message the text to show in the result label
     */
    void showMessage(String message);

    /**
     * @param enabled whether the Remove button may be pressed
     */
    void setRemoveEnabled(boolean enabled);
}
