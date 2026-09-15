package free.svoss.facesort.ui;

/**
 * Implemented by views whose content is derived from the database and can go
 * stale while the user works in other tabs.
 *
 * <p>{@link #refresh()} reloads the view's primary data in a background task
 * so that lists and counts reflect changes made elsewhere (imports, tagging,
 * deduplication) as soon as the tab is shown again. Implementations must be
 * safe to call repeatedly while the view is visible.</p>
 *
 * <p>Views that do not display database-derived data (Import, Settings) or
 * that manage a stateful working session that should only be reset by the
 * user (Deduplicate) intentionally do not implement this interface.</p>
 */
public interface Refreshable {

    /**
     * Reloads this view's primary data from the database.
     */
    void refresh();
}