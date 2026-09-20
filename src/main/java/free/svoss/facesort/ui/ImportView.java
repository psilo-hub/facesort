package free.svoss.facesort.ui;

import free.svoss.facesort.config.AppConfig;
import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.i18n.I18n;
import free.svoss.facesort.service.ImportCoordinator;
import free.svoss.facesort.service.ImportService;
import free.svoss.facesort.service.VideoImportService;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Import tab: pick a folder, run the import pipeline, watch progress.
 *
 * <p>The view owns no business logic; it delegates all work to
 * {@link ImportService} (photos) and {@link VideoImportService} (videos),
 * sequenced by {@link ImportCoordinator}. Importing runs on a background
 * thread via a {@link Task}, progress messages are forwarded to the log area
 * on the JavaFX application thread, and the combined summary is displayed in
 * the status label when the run finishes.</p>
 *
 * <p>While an import runs, a Stop button can request cancellation: the shared
 * flag is honored by both phases between files and during folder scanning,
 * and already-imported rows are kept.</p>
 */
public class ImportView extends BorderPane {

    private final ImportService importService;
    private final VideoImportService videoImportService;
    private final ConfigModel config;

    private final TextField folderField = new TextField();
    private final Button browseButton = new Button(I18n.get("ui.import.browse"));
    private final Button importButton = new Button(I18n.get("ui.import.import"));
    private final Button stopButton = new Button(I18n.get("ui.import.stop"));
    private final ProgressBar progressBar = new ProgressBar(0);
    private final TextArea logArea = new TextArea();
    private final Label statusLabel = new Label(I18n.get("ui.import.ready"));

    private volatile boolean cancelRequested;

    /**
     * Creates the Import tab.
     *
     * @param importService      the photo import pipeline; must not be null
     * @param videoImportService the video import pipeline; must not be null
     * @param config             application configuration used to remember the last
     *                           import folder; must not be null
     */
    public ImportView(ImportService importService, VideoImportService videoImportService,
                      ConfigModel config) {
        this.importService = importService;
        this.videoImportService = videoImportService;
        this.config = config;
        buildUi();
        restoreLastFolder();
    }

    /**
     * Builds the folder picker, import/stop buttons, progress bar, log and status.
     */
    private void buildUi() {
        // Top: folder selection + import/stop buttons
        Label folderLabel = new Label(I18n.get("ui.import.folder"));
        folderField.setPromptText(I18n.get("ui.import.folderPrompt"));
        HBox.setHgrow(folderField, Priority.ALWAYS);
        browseButton.setOnAction(e -> onBrowse());
        importButton.setDefaultButton(true);
        importButton.setOnAction(e -> onImport());
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> onStop());

        HBox topBar = new HBox(8, folderLabel, folderField, browseButton, importButton, stopButton);
        topBar.setPadding(new Insets(10));
        topBar.setAlignment(Pos.CENTER_LEFT);

        // Center: log area with progress bar above it
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPromptText(I18n.get("ui.import.logPrompt"));
        VBox.setVgrow(logArea, Priority.ALWAYS);

        progressBar.setMaxWidth(Double.MAX_VALUE);
        VBox center = new VBox(6, progressBar, logArea);
        center.setPadding(new Insets(0, 10, 10, 10));

        // Bottom: status summary
        statusLabel.setWrapText(true);
        BorderPane.setMargin(statusLabel, new Insets(0, 10, 10, 10));

        setTop(topBar);
        setCenter(center);
        setBottom(statusLabel);
    }

    /**
     * Restores the last import folder from the configuration, if any.
     */
    private void restoreLastFolder() {
        String last = config.getLastImportFolder();
        if (last != null && !last.isBlank()) {
            folderField.setText(last);
        }
    }

    /**
     * Opens a directory chooser and stores the selection in the folder field.
     */
    private void onBrowse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("ui.import.folderChooserTitle"));
        String current = folderField.getText();
        if (current != null && !current.isBlank()) {
            Path path = Path.of(current);
            if (Files.isDirectory(path)) {
                chooser.setInitialDirectory(path.toFile());
            }
        }
        Window owner = getScene() != null ? getScene().getWindow() : null;
        java.io.File selected = chooser.showDialog(owner);
        if (selected != null) {
            folderField.setText(selected.getAbsolutePath());
        }
    }

    /**
     * Starts the import in the background when the Import button is pressed.
     */
    private void onImport() {
        Path folder = Path.of(folderField.getText().trim());
        if (!Files.isDirectory(folder)) {
            statusLabel.setText(I18n.get("ui.import.invalidFolder"));
            return;
        }
        startImport(folder);
    }

    /**
     * Requests the running import to stop as soon as the current image is done.
     */
    private void onStop() {
        cancelRequested = true;
        stopButton.setDisable(true);
        appendLog(I18n.get("ui.import.stopRequested"));
    }

    /**
     * Runs the combined photo + video import pipeline on a background thread,
     * streaming progress messages from both phases to the log area and showing
     * the combined summary when complete.
     *
     * @param folder the root directory to scan
     */
    private void startImport(Path folder) {
        rememberFolder(folder);
        cancelRequested = false;

        setBusy(true);
        statusLabel.setText(I18n.get("ui.import.scanning"));
        progressBar.setProgress(-1);
        appendLog(I18n.format("ui.import.importingFrom", folder.toAbsolutePath()));

        Task<ImportCoordinator.CombinedImportResult> task = new Task<>() {
            @Override
            protected ImportCoordinator.CombinedImportResult call() throws Exception {
                return ImportCoordinator.run(importService, videoImportService, folder,
                        message -> Platform.runLater(() -> appendLog(message)),
                        () -> cancelRequested,
                        () -> Platform.runLater(
                                () -> appendLog(I18n.get("ui.import.videoPhase"))));
            }
        };

        task.setOnSucceeded(e -> {
            ImportCoordinator.CombinedImportResult result = task.getValue();
            setBusy(false);
            cancelRequested = false;
            if (result.wasCancelled()) {
                progressBar.setProgress(result.total() > 0
                        ? (double) result.processed() / result.total() : 0);
                String stopped = I18n.format("ui.import.stoppedAfter",
                        result.processed(), result.total());
                statusLabel.setText(stopped + System.lineSeparator()
                        + formatSummaries(result));
                appendLog(I18n.get("ui.import.stopped"));
            } else {
                progressBar.setProgress(1);
                statusLabel.setText(formatSummaries(result));
                appendLog(I18n.get("ui.import.finished"));
            }
        });

        task.setOnFailed(e -> {
            setBusy(false);
            cancelRequested = false;
            progressBar.setProgress(0);
            Throwable error = task.getException();
            String detail = error instanceof IOException ? error.getMessage() : String.valueOf(error);
            statusLabel.setText(I18n.format("ui.import.failed", detail));
            appendLog(I18n.format("ui.import.failed", error));
        });

        Thread thread = new Thread(task, "import-worker");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Disables the picker and import controls (and re-enables Stop) while a
     * scan runs; restores them when the run finishes.
     *
     * @param busy {@code true} while a scan is running
     */
    private void setBusy(boolean busy) {
        browseButton.setDisable(busy);
        importButton.setDisable(busy);
        folderField.setDisable(busy);
        stopButton.setDisable(!busy);
    }

    /**
     * Remembers the chosen folder in the config and persists it.
     *
     * @param folder the folder being imported
     */
    private void rememberFolder(Path folder) {
        config.setLastImportFolder(folder.toAbsolutePath().toString());
        try {
            AppConfig.save(Path.of(AppConfig.DEFAULT_CONFIG_FILE), config);
        } catch (IOException e) {
            // Remembering the folder is best-effort; do not fail the import.
            appendLog(I18n.format("ui.import.configSaveFailed", e.getMessage()));
        }
    }

    /**
     * Appends a line to the log area (must be called on the FX thread).
     *
     * @param line the message to append
     */
    private void appendLog(String line) {
        logArea.appendText(line + System.lineSeparator());
    }

    /**
     * Formats the photo and video summaries as two status lines.
     *
     * @param result the combined import result
     * @return a two-line human-readable summary string
     */
    private static String formatSummaries(ImportCoordinator.CombinedImportResult result) {
        return formatSummary(result.images()) + System.lineSeparator()
                + formatVideoSummary(result.videos());
    }

    /**
     * Formats the import summary as a single status line.
     *
     * @param result the import result
     * @return a human-readable summary string
     */
    private static String formatSummary(ImportService.ImportResult result) {
        return I18n.format("ui.import.summary",
                result.totalFiles(), result.newImages(), result.newPaths(),
                result.newFaces(), result.skipped(), result.errors());
    }

    /**
     * Formats the video import summary as a single status line.
     *
     * @param result the video import result
     * @return a human-readable summary string
     */
    private static String formatVideoSummary(VideoImportService.VideoImportResult result) {
        return I18n.format("ui.import.videoSummary",
                result.totalVideos(), result.newVideos(), result.newFrames(),
                result.newFaces(), result.skipped(), result.errors());
    }
}