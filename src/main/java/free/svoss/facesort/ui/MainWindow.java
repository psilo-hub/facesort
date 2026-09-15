package free.svoss.facesort.ui;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * Main application window: a tab pane containing all application views.
 * Tabs whose content implements {@link Refreshable} reload their primary data
 * whenever they are selected, so lists and counts reflect changes made in
 * other tabs (imports, tagging, deduplication).
 */
public class MainWindow extends TabPane {

    /**
     * Creates the main window with the given tabs.
     *
     * @param tabs the tabs to include
     */
    public MainWindow(Tab... tabs) {
        getTabs().addAll(tabs);
        setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
        getSelectionModel().selectedItemProperty().addListener(
                (obs, oldTab, newTab) -> {
                    if (newTab != null && newTab.getContent() instanceof Refreshable refreshable) {
                        refreshable.refresh();
                    }
                });
    }
}
