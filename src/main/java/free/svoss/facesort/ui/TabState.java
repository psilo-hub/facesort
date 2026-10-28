package free.svoss.facesort.ui;

/**
 * Shared UI state carried across tabs, held by the main window and passed to
 * the tabs that need it.
 *
 * <p>It remembers the name last tagged by the user (so the "add faces to a
 * name" tab can jump to it when opened) and the last path filter set by the
 * user (so any tab with a path filter picks it up when opened). Both are plain
 * fields accessed on the JavaFX application thread.</p>
 */
public class TabState {

    /** The name last tagged in a naming tab, or {@code null} when none is pending. */
    private String lastTaggedName;

    /** The last path filter set in a tab, or {@code null} when none has been set. */
    private String lastPathFilter;

    /**
     * Returns and clears the last tagged name. Consumes the pending navigation
     * hint so it is applied at most once when the "add faces to a name" tab is
     * opened.
     *
     * @return the pending tagged name, or {@code null} when none
     */
    public String takeLastTaggedName() {
        String name = lastTaggedName;
        lastTaggedName = null;
        return name;
    }

    /** Stores the name most recently tagged by the user. */
    public void setLastTaggedName(String name) {
        lastTaggedName = name;
    }

    /** Returns the last path filter set by the user, or {@code null} when none. */
    public String getLastPathFilter() {
        return lastPathFilter;
    }

    /**
     * Stores the path filter most recently set by the user. Blank values are
     * ignored so clearing a filter in one tab does not wipe it from another.
     *
     * @param pathFilter the path filter to remember, may be blank
     */
    public void setLastPathFilter(String pathFilter) {
        if (pathFilter != null && !pathFilter.isBlank()) {
            lastPathFilter = pathFilter;
        }
    }
}
