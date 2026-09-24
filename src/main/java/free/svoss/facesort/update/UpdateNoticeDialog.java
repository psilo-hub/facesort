package free.svoss.facesort.update;

import free.svoss.facesort.i18n.I18n;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simple, non-modal dialog announcing that a new release is available.
 *
 * <p>It is independent of the main window, shows the version and the
 * auto-generated release notes of the new release and contains a clickable link
 * to the release that opens in the system's default browser. The dialog is
 * closable either via its close button or the window's close control.</p>
 */
public class UpdateNoticeDialog extends Dialog<Void> {

    private static final Logger LOG = Logger.getLogger(UpdateNoticeDialog.class.getName());

    /** URL of the latest release page on GitHub; fallback for releases without one. */
    public static final String RELEASES_URL = "https://github.com/psilo-hub/facesort/releases/latest";

    /**
     * Creates the update notice dialog for the given release.
     *
     * @param release the latest release to announce; must not be null
     */
    public UpdateNoticeDialog(UpdateChecker.ReleaseInfo release) {
        setTitle(I18n.get("update.title"));
        setResizable(false);

        Label message = new Label(I18n.format("update.messageVersion", release.tagName()));
        message.setWrapText(true);

        VBox content = new VBox(8, message);
        if (release.notes() != null && !release.notes().isBlank()) {
            Label notesHeader = new Label(I18n.get("update.releaseNotes"));
            TextArea notes = new TextArea(release.notes());
            notes.setEditable(false);
            notes.setWrapText(true);
            notes.setPrefHeight(220);
            content.getChildren().addAll(notesHeader, notes);
        }

        String releaseUrl = release.htmlUrl() != null && !release.htmlUrl().isBlank()
                ? release.htmlUrl() : RELEASES_URL;
        Hyperlink releasesLink = new Hyperlink(releaseUrl);
        releasesLink.setOnAction(event -> openInBrowser(releaseUrl));

        content.getChildren().add(releasesLink);
        content.setPrefWidth(420);
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