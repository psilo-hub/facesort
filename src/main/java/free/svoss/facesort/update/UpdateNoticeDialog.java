package free.svoss.facesort.update;

import free.svoss.facesort.i18n.I18n;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simple, non-modal dialog announcing that a new release is available.
 *
 * <p>It is independent of the main window and contains a clickable link to the
 * latest release that opens in the system's default browser. The dialog is
 * closable either via its close button or the window's close control.</p>
 */
public class UpdateNoticeDialog extends Dialog<Void> {

    private static final Logger LOG = Logger.getLogger(UpdateNoticeDialog.class.getName());

    /** URL of the latest release page on GitHub. */
    public static final String RELEASES_URL = "https://github.com/psilo-hub/facesort/releases/latest";

    /**
     * Creates the update notice dialog.
     */
    public UpdateNoticeDialog() {
        setTitle(I18n.get("update.title"));
        setResizable(false);

        Label message = new Label(I18n.get("update.message"));
        message.setWrapText(true);

        Hyperlink releasesLink = new Hyperlink(RELEASES_URL);
        releasesLink.setOnAction(event -> openInBrowser(RELEASES_URL));

        VBox content = new VBox(8, message, releasesLink);
        content.setPrefWidth(380);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    }

    private static void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                LOG.log(Level.WARNING, "Update check: no desktop browser support for {0}", url);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to open the release page in the browser", e);
        }
    }
}