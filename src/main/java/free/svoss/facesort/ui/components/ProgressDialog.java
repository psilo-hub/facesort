package free.svoss.facesort.ui.components;

import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.Objects;

/**
 * A modal progress dialog with a status label, a progress bar, and a
 * scrolling log area for background tasks.
 *
 * <p>Intended for long-running operations such as importing folders:
 * {@code appendLog} records what is happening, {@code setStatus} updates the
 * short status line, and {@code setProgress} drives the bar (0.0-1.0, or
 * {@code -1} for an indeterminate bar). The dialog carries no buttons — it is
 * closed programmatically when the background task finishes.</p>
 */
public class ProgressDialog extends Dialog<Void> {

    private static final double MAX_LOG_HEIGHT = 200;

    private final Label statusLabel;
    private final ProgressBar progressBar;
    private final TextArea logArea;

    /**
     * Creates a modal progress dialog owned by the given window.
     *
     * @param owner the parent window, or {@code null} for a standalone dialog
     * @param title the dialog title; must not be null
     */
    public ProgressDialog(Window owner, String title) {
        Objects.requireNonNull(title, "title");
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setTitle(title);
        setResizable(false);

        statusLabel = new Label();
        statusLabel.setWrapText(true);

        progressBar = new ProgressBar(0.0);
        progressBar.setPrefWidth(360);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefHeight(MAX_LOG_HEIGHT);

        VBox content = new VBox(8, statusLabel, progressBar, logArea);
        content.setPrefWidth(400);
        getDialogPane().setContent(content);
    }

    /**
     * Appends a line to the log area.
     *
     * @param line the line to append; null and empty lines are ignored
     */
    public void appendLog(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        logArea.appendText(line + System.lineSeparator());
    }

    /**
     * Sets the progress bar value.
     *
     * @param value progress in [0.0, 1.0], or {@code -1} for an indeterminate bar
     */
    public void setProgress(double value) {
        if (value < 0) {
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        } else {
            progressBar.setProgress(Math.min(1.0, value));
        }
    }

    /**
     * Sets the short status message shown above the progress bar.
     *
     * @param message the status text; must not be null
     */
    public void setStatus(String message) {
        statusLabel.setText(Objects.requireNonNull(message, "message"));
    }

    /**
     * Returns the current status text (test/UI introspection helper).
     *
     * @return the status label text
     */
    public String getStatus() {
        return statusLabel.getText();
    }

    /**
     * Returns the complete log text (test/UI introspection helper).
     *
     * @return the log area text
     */
    public String getLogText() {
        return logArea.getText();
    }
}