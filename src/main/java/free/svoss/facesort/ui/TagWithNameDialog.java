package free.svoss.facesort.ui;

import free.svoss.facesort.i18n.I18n;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.service.FaceToNameService;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Modal dialog for tagging a face with a name other than the one it was offered
 * under.
 *
 * <p>The dialog shows the face about to be tagged, a text field for the name,
 * and OK / Cancel buttons. The OK button stays disabled while the name is
 * empty. As the user types, the dialog asks the {@link NameLookup} whether the
 * name already exists: a new name is announced with the "New name" hint, while
 * an existing name shows the face tagged with it that is closest to that name's
 * average embedding and the similarity between the selected face and the
 * name's average embedding. Lookups run on a background daemon thread and stale
 * results are ignored once the field changes.</p>
 *
 * <p>The dialog is owned by the given window and returns the trimmed name
 * chosen via OK, or {@code null} when cancelled or closed.</p>
 */
public class TagWithNameDialog extends Dialog<String> {

    private static final double FACE_SIZE = 160.0;
    private static final double PREVIEW_SIZE = 90.0;

    private final TextField nameField = new TextField();
    private final Label hintLabel = new Label("");
    private final VBox previewBox;
    private final ImageView previewThumb = new ImageView();
    private final Label previewSimilarityLabel = new Label("");

    private long lookupGeneration;

    /**
     * Looks up the preview for a typed name on behalf of the dialog.
     */
    @FunctionalInterface
    public interface NameLookup {
        /**
         * @param name the trimmed name typed into the field; never blank
         * @return the preview for an existing name, empty for a new name
         * @throws SQLException on database access failure
         */
        Optional<FaceToNameService.NamePreview> lookup(String name) throws SQLException;
    }

    /**
     * Creates the "tag with a different name" dialog.
     *
     * @param owner        the parent window, or {@code null} for a standalone dialog
     * @param selectedFace the face the user wants to tag; must not be null
     * @param lookup       the name lookup to call while the user types; must not be null
     */
    public TagWithNameDialog(Window owner, FaceRecord selectedFace, NameLookup lookup) {
        Objects.requireNonNull(selectedFace, "selectedFace");
        Objects.requireNonNull(lookup, "lookup");

        setTitle(I18n.get("ui.faceName.differentName.title"));
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(false);

        Label header = new Label(I18n.get("ui.faceName.differentName.header"));
        header.setWrapText(true);
        header.setStyle("-fx-font-weight: bold;");

        ImageView selectedView = new ImageView();
        selectedView.setImage(toImage(selectedFace));
        selectedView.setFitWidth(FACE_SIZE);
        selectedView.setFitHeight(FACE_SIZE);
        selectedView.setPreserveRatio(true);
        selectedView.setSmooth(true);

        nameField.setPromptText(I18n.get("ui.faceName.differentName.namePrompt"));
        HBox.setHgrow(nameField, javafx.scene.layout.Priority.ALWAYS);
        HBox nameRow = new HBox(8, new Label(I18n.get("ui.faceName.differentName.nameLabel")), nameField);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        nameRow.setPadding(new Insets(0, 0, 4, 0));

        hintLabel.setWrapText(true);
        hintLabel.setStyle("-fx-text-fill: #3c763d;");

        previewThumb.setFitWidth(PREVIEW_SIZE);
        previewThumb.setFitHeight(PREVIEW_SIZE);
        previewThumb.setPreserveRatio(true);
        previewThumb.setSmooth(true);
        previewSimilarityLabel.setWrapText(true);
        previewSimilarityLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #666666;");
        previewBox = new VBox(6, previewThumb, previewSimilarityLabel);
        previewBox.setVisible(false);
        previewBox.setAlignment(Pos.TOP_CENTER);

        VBox content = new VBox(8, header, selectedView, nameRow, hintLabel, previewBox);
        content.setPrefWidth(380);
        content.setAlignment(Pos.TOP_CENTER);
        getDialogPane().setContent(content);

        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setText(I18n.get("ui.faceName.differentName.ok"));
        okButton.setDefaultButton(true);
        okButton.disableProperty().bind(nameField.textProperty().isEmpty());

        setResultConverter(dialogButton -> {
            if (dialogButton == ButtonType.OK) {
                String typed = nameField.getText() == null ? "" : nameField.getText().trim();
                return typed.isEmpty() ? null : typed;
            }
            return null;
        });

        nameField.textProperty().addListener(
                (obs, oldText, newText) -> onNameChanged(lookup));
    }

    /**
     * Runs a background lookup whenever the typed name is non-blank and updates
     * the hint and the existing-name preview when the result is still current.
     *
     * @param lookup the name lookup to call
     */
    private void onNameChanged(NameLookup lookup) {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            hintLabel.setText("");
            previewBox.setVisible(false);
            return;
        }
        final long generation = ++lookupGeneration;
        Task<Optional<FaceToNameService.NamePreview>> task = new Task<>() {
            @Override
            protected Optional<FaceToNameService.NamePreview> call() throws SQLException {
                return lookup.lookup(name);
            }
        };
        task.setOnSucceeded(e -> {
            if (generation != lookupGeneration) {
                return; // the field changed again; ignore the stale result
            }
            Optional<FaceToNameService.NamePreview> preview = task.getValue();
            if (preview.isEmpty()) {
                hintLabel.setText(I18n.get("ui.faceName.differentName.newName"));
                hintLabel.setStyle("-fx-text-fill: #3c763d;");
                previewBox.setVisible(false);
            } else {
                FaceToNameService.NamePreview p = preview.get();
                hintLabel.setText(I18n.get("ui.faceName.differentName.exists"));
                hintLabel.setStyle("-fx-text-fill: #c9302c;");
                previewThumb.setImage(toImage(p.representative()));
                previewSimilarityLabel.setText(
                        I18n.format("ui.faceName.differentName.similar", name, p.similarity() * 100));
                previewBox.setVisible(true);
            }
        });
        task.setOnFailed(e -> {
            // the lookup failing should not block tagging; keep the last state
        });
        Thread thread = new Thread(task, "facename-different-name-lookup");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Decodes a face's JPEG sub-image, or returns {@code null} when none is
     * available.
     *
     * @param face the face whose thumbnail to decode
     * @return the decoded image, or {@code null}
     */
    private static Image toImage(FaceRecord face) {
        byte[] jpg = face != null ? face.subImageJpg() : null;
        if (jpg == null || jpg.length == 0) {
            return null;
        }
        return new Image(new ByteArrayInputStream(jpg));
    }
}