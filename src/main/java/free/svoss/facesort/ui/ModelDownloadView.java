package free.svoss.facesort.ui;

import free.svoss.facesort.i18n.I18n;
import free.svoss.tools.faceai.ModelDownloadListener;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.Objects;

/**
 * Startup "frame" shown while the FaceAI models are downloaded on first run.
 *
 * <p>The frame states which file (URL) is being downloaded and where it is
 * stored, and shows a progress bar while the transfer runs. It is displayed in
 * the primary stage before the main window is built, so the user never has to
 * watch the app apparently freeze during the first-time download.</p>
 *
 * <p>All UI mutations happen on the JavaFX application thread; the download
 * runs on a background thread and forwards events through
 * {@link #listener()}.</p>
 */
public class ModelDownloadView extends VBox {

    private final Label titleLabel = new Label(I18n.get("ui.modelDownload.title"));
    private final Label hintLabel = new Label(I18n.get("ui.modelDownload.hint"));
    private final Label currentLabel = new Label(I18n.get("ui.modelDownload.waiting"));
    private final Label urlCaption = new Label(I18n.get("ui.modelDownload.downloadingFrom"));
    private final Label urlLabel = new Label();
    private final VBox urlRow = new VBox(2);
    private final Label destinationCaption = new Label(I18n.get("ui.modelDownload.storedIn"));
    private final Label destinationLabel = new Label();
    private final VBox destinationRow = new VBox(2);
    private final Label percentLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final TextArea logArea = new TextArea();

    private long lastDownloadedBytes = -1;
    private long lastTotalBytes = -1;

    /**
     * Creates the download frame.
     *
     * @param destinationDirectory the directory the models are stored under;
     *                             must not be null
     */
    public ModelDownloadView(String destinationDirectory) {
        super(10);
        setAlignment(Pos.TOP_LEFT);
        setPadding(new Insets(20));
        setMaxWidth(Double.MAX_VALUE);

        titleLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        hintLabel.setWrapText(true);
        hintLabel.setMaxWidth(620);

        urlLabel.setWrapText(true);
        urlLabel.setMaxWidth(620);
        urlLabel.getStyleClass().add("download-url");
        destinationLabel.setWrapText(true);
        destinationLabel.setMaxWidth(620);
        destinationLabel.getStyleClass().add("download-destination");

        urlCaption.getStyleClass().add("download-caption");
        destinationCaption.getStyleClass().add("download-caption");

        progressBar.setPrefWidth(600);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        urlRow.getChildren().addAll(urlCaption, urlLabel);
        urlRow.setVisible(false);
        urlRow.setManaged(false);
        destinationRow.getChildren().addAll(destinationCaption, destinationLabel);
        destinationRow.setVisible(false);
        destinationRow.setManaged(false);

        destinationLabel.setText(destinationDirectory);

        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefHeight(140);
        logArea.setPromptText(I18n.get("ui.modelDownload.logPrompt"));

        currentLabel.setWrapText(true);
        currentLabel.setMaxWidth(620);

        getChildren().addAll(
                titleLabel, hintLabel, currentLabel,
                urlRow, destinationRow, progressBar, percentLabel, logArea);
        setVgrow(logArea, Priority.ALWAYS);
    }

    /**
     * Returns a listener that forwards download events onto the JavaFX
     * application thread. Safe to register on a {@code FaceAIConfig} used from
     * a background download thread.
     *
     * @return a new listener adapter for this view
     */
    public ModelDownloadListener listener() {
        return new ModelDownloadListener() {
            @Override
            public void onModelDownloadStarted(String modelName, String url,
                    String destinationDirectory, long totalBytes) {
                Platform.runLater(() -> showDownload(modelName, url, destinationDirectory, totalBytes));
            }

            @Override
            public void onModelDownloadProgress(String modelName,
                    long downloadedBytes, long totalBytes) {
                Platform.runLater(() -> showProgress(modelName, downloadedBytes, totalBytes));
            }

            @Override
            public void onModelDownloaded(String modelName) {
                Platform.runLater(() -> {
                    appendLogOnFxThread(I18n.format("ui.modelDownload.downloaded", modelName));
                    if (lastDownloadedBytes != -1) {
                        progressBar.setProgress(1);
                    }
                });
            }
        };
    }

    private void showDownload(String modelName, String url,
            String destinationDirectory, long totalBytes) {
        lastDownloadedBytes = 0;
        lastTotalBytes = totalBytes;
        currentLabel.setText(I18n.format("ui.modelDownload.downloading", modelName));
        urlLabel.setText(url);
        urlRow.setVisible(true);
        urlRow.setManaged(true);
        destinationLabel.setText(destinationDirectory);
        destinationRow.setVisible(true);
        destinationRow.setManaged(true);
        updateProgress();
        appendLogOnFxThread(I18n.format("ui.modelDownload.downloadingFromUrl", modelName, url));
    }

    private void showProgress(String modelName, long downloadedBytes, long totalBytes) {
        lastDownloadedBytes = downloadedBytes;
        lastTotalBytes = totalBytes;
        currentLabel.setText(I18n.format("ui.modelDownload.downloading", modelName));
        updateProgress();
    }

    private void updateProgress() {
        if (lastTotalBytes > 0) {
            double fraction = Math.min(1.0, (double) lastDownloadedBytes / lastTotalBytes);
            progressBar.setProgress(fraction);
            percentLabel.setText(I18n.format("ui.modelDownload.percentOf",
                    fraction * 100, formatBytes(lastTotalBytes)));
        } else {
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            percentLabel.setText(lastDownloadedBytes > 0
                    ? formatBytes(lastDownloadedBytes)
                    : I18n.get("ui.modelDownload.sizeUnknown"));
        }
    }

    /**
     * Sets the status message shown under the title (may be called from any
     * thread; the view forwards it to the FX thread).
     *
     * @param message the status text; must not be null
     */
    public void setStatus(String message) {
        Objects.requireNonNull(message, "message");
        Platform.runLater(() -> currentLabel.setText(message));
    }

    /**
     * Appends a line to the download log (may be called from any thread).
     *
     * @param line the line to append; must not be null
     */
    public void appendLog(String line) {
        Objects.requireNonNull(line, "line");
        Platform.runLater(() -> appendLogOnFxThread(line));
    }

    private void appendLogOnFxThread(String line) {
        logArea.appendText(line + System.lineSeparator());
    }

    /**
     * Formats a byte count as a human readable size.
     *
     * @param bytes the byte count
     * @return a compact size string
     */
    static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        String unit = "B";
        for (String u : units) {
            value /= 1024;
            if (value < 1024 || u.equals("GB")) {
                unit = u;
                break;
            }
        }
        return String.format(Locale.ROOT, "%.1f %s", value, unit);
    }
}