package free.svoss.facesort.ui;

import free.svoss.facesort.i18n.I18n;
import free.svoss.facesort.service.DataRemovalService;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.Objects;

/**
 * Modal dialog for removing imported images and videos by path prefix.
 *
 * <p>The user types a prefix and presses <em>Preview</em>; the dialog then
 * counts, on a background thread, how many images, videos, thumbnails and face
 * sub-images would be removed and shows the numbers. Only after the user
 * confirms with <em>Remove</em> is anything deleted. Editing the prefix
 * invalidates the preview until it is scanned again.</p>
 *
 * <p>Results: {@code showAndWait()} returns the {@link DataRemovalService.Removal}
 * that was executed, or {@code null} when the dialog was cancelled or closed
 * (including a preview that matched nothing, which has nothing to remove).</p>
 */
public class RemoveByPrefixDialog extends Dialog<DataRemovalService.Removal> {

    private final DataRemovalService service;

    private final TextField prefixField = new TextField();
    private final Label resultLabel = new Label();
    private final Button scanButton = new Button(I18n.get("ui.import.removeByPrefix.scan"));
    private final Button removeButton = new Button(I18n.get("ui.import.removeByPrefix.remove"));
    private final Button cancelButton = new Button(I18n.get("ui.import.removeByPrefix.cancel"));

    private final TaskRunner taskRunner = new TaskRunner();
    private DataRemovalService.Removal performed;

    /**
     * @param owner   the parent window, or {@code null} for a standalone dialog
     * @param service the removal service; must not be null
     */
    public RemoveByPrefixDialog(Window owner, DataRemovalService service) {
        this.service = Objects.requireNonNull(service, "service");
        setTitle(I18n.get("ui.import.removeByPrefix.title"));
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(false);
        buildUi();
        setResultConverter(dialogButton -> performed);
    }

    private void buildUi() {
        Label header = new Label(I18n.get("ui.import.removeByPrefix.header"));
        header.setWrapText(true);
        header.setStyle("-fx-font-weight: bold;");

        Label prefixLabel = new Label(I18n.get("ui.import.removeByPrefix.prefixLabel"));
        prefixField.setPromptText(I18n.get("ui.import.removeByPrefix.prefixPrompt"));
        HBox.setHgrow(prefixField, Priority.ALWAYS);
        HBox prefixRow = new HBox(8, prefixLabel, prefixField);
        prefixRow.setPadding(new Insets(0, 0, 8, 0));

        resultLabel.setWrapText(true);

        scanButton.setDisable(true);
        scanButton.setOnAction(e -> onScan());
        removeButton.setDisable(true);
        removeButton.setOnAction(e -> onRemove());
        cancelButton.setOnAction(e -> close());

        HBox buttons = new HBox(8, scanButton, removeButton, cancelButton);
        buttons.setPadding(new Insets(10, 0, 0, 0));

        VBox content = new VBox(8, header, prefixRow, resultLabel, buttons);
        content.setPrefWidth(460);
        getDialogPane().setContent(content);

        prefixField.textProperty().addListener((obs, oldText, newText) -> onPrefixChanged());
        prefixField.setOnAction(e -> onScan());
    }

    /**
     * Invalidates the current preview when the prefix changes while the dialog
     * is still usable.
     */
    private void onPrefixChanged() {
        resultLabel.setText("");
        removeButton.setDisable(true);
        scanButton.setDisable(prefixField.getText() == null || prefixField.getText().isBlank());
    }

    /**
     * Counts the rows a removal would delete and shows the preview, without
     * changing anything.
     */
    private void onScan() {
        String prefix = prefixField.getText() == null ? "" : prefixField.getText().trim();
        if (prefix.isEmpty()) {
            return; // the button is disabled for an empty prefix
        }
        setBusy(true);
        resultLabel.setText(I18n.get("ui.import.removeByPrefix.scanning"));

        Task<DataRemovalService.Estimate> task = taskRunner.start(new Task<>() {
            @Override
            protected DataRemovalService.Estimate call() throws Exception {
                return service.estimate(prefix);
            }
        }, "removal-scan");

        task.setOnSucceeded(e -> {
            DataRemovalService.Estimate estimate = task.getValue();
            if (estimate == null) {
                return;
            }
            setBusy(false);
            if (!estimate.hasMatches()) {
                resultLabel.setText(I18n.get("ui.import.removeByPrefix.nothingMatched"));
            } else {
                resultLabel.setText(I18n.format("ui.import.removeByPrefix.estimate",
                        estimate.images(), estimate.videos(),
                        estimate.thumbnails(), estimate.faceSubImages()));
                removeButton.setDisable(false);
            }
        });
        task.setOnFailed(e -> {
            setBusy(false);
            resultLabel.setText(I18n.format("ui.import.removeByPrefix.scanFailed",
                    failureMessage(task)));
        });
    }

    /**
     * Deletes the scanned rows after the user confirmed. The dialog closes with
     * the executed removal as its result.
     */
    private void onRemove() {
        String prefix = prefixField.getText() == null ? "" : prefixField.getText().trim();
        if (prefix.isEmpty()) {
            return;
        }
        setBusy(true);
        resultLabel.setText(I18n.get("ui.import.removeByPrefix.removing"));

        Task<DataRemovalService.Removal> task = taskRunner.start(new Task<>() {
            @Override
            protected DataRemovalService.Removal call() throws Exception {
                return service.remove(prefix);
            }
        }, "removal-run");

        task.setOnSucceeded(e -> {
            performed = task.getValue();
            close();
        });
        task.setOnFailed(e -> {
            setBusy(false);
            resultLabel.setText(I18n.format("ui.import.removeByPrefix.removeFailed",
                    failureMessage(task)));
        });
    }

    /**
     * Disables the whole dialog while a scan or a removal runs in the
     * background.
     *
     * @param busy {@code true} while a background operation runs
     */
    private void setBusy(boolean busy) {
        boolean hasPrefix = prefixField.getText() != null && !prefixField.getText().isBlank();
        prefixField.setDisable(busy);
        scanButton.setDisable(busy || !hasPrefix);
        removeButton.setDisable(true); // invalidate the preview while busy
        cancelButton.setDisable(busy);
    }

    private static String failureMessage(Task<?> task) {
        Throwable error = task.getException();
        return error != null && error.getMessage() != null
                ? error.getMessage()
                : I18n.get("app.unknownError");
    }
}