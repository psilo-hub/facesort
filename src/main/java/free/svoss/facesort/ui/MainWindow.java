package free.svoss.facesort.ui;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * Main application window: a tab pane containing all application views.
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
    }
}
