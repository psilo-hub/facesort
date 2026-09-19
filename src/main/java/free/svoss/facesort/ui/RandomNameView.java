package free.svoss.facesort.ui;

import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.service.NamingService;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The "Tag random face" tab.
 *
 * <p>Shows a random sample of unnamed faces as a grid. The user can browse
 * through fresh random samples with "Next", and tag any of the shown faces by
 * selecting them and entering a name. Tagged faces disappear from the current
 * sample immediately.</p>
 *
 * <p>The view owns no business logic; sampling and tagging are delegated to
 * {@link NamingService}. Queries run on background {@link Task}s so the UI
 * stays responsive.</p>
 */
public class RandomNameView extends BorderPane implements Refreshable {

    private static final double THUMBNAIL_SIZE = 110.0;
    private static final int BATCH_SIZE = 25;

    private final NamingService namingService;

    private final Label statusLabel = new Label("");
    private final TextField nameField = new TextField();
    private final TextField pathFilterField = new TextField();
    private final Label nameExistsLabel = new Label("");
    private final Button tagSelectedButton = new Button("Tag selected");
    private final Button nextButton = new Button("Next");
    private final FlowPane facesPane = new FlowPane(10, 10);

    private Task<?> activeTask;

    /**
     * Creates the random-tagging tab.
     *
     * @param namingService the naming service; must not be null
     */
    public RandomNameView(NamingService namingService) {
        this.namingService = namingService;
        buildUi();
        loadSample();
    }

    /**
     * Builds the name input row, the scrolling face grid and the status bar.
     */
    private void buildUi() {
        nameField.setPromptText("Enter a name");
        tagSelectedButton.getStyleClass().add("primary");
        tagSelectedButton.setDisable(true);
        tagSelectedButton.setOnAction(e -> onTagSelected());
        nextButton.setOnAction(e -> loadSample());

        pathFilterField.setPromptText("Filter by path prefix");
        pathFilterField.setTooltip(new Tooltip("Restrict the random sample to images whose "
                + "stored path starts with the entered text. Press Enter to apply."));
        pathFilterField.setOnAction(e -> loadSample());
        HBox.setHgrow(pathFilterField, Priority.ALWAYS);
        HBox.setHgrow(nameField, Priority.ALWAYS);

        nameExistsLabel.setWrapText(true);
        nameField.textProperty().addListener((obs, oldText, newText) -> checkNameExists());
        HBox controls = new HBox(8,
                new Label("Name:"), nameField, nameExistsLabel, tagSelectedButton, nextButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(10));

        HBox filterBar = new HBox(8, new Label("Path filter:"), pathFilterField);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setPadding(new Insets(10));

        facesPane.setPadding(new Insets(10));

        ScrollPane scroll = new ScrollPane(facesPane);
        scroll.setFitToWidth(true);

        statusLabel.setWrapText(true);
        BorderPane.setMargin(statusLabel, new Insets(0, 10, 10, 10));

        VBox topBox = new VBox(controls, filterBar);
        setTop(topBox);
        setCenter(scroll);
        setBottom(statusLabel);
    }

    /**
     * Loads a fresh random sample of unnamed faces in the background and
     * renders it as a grid.
     */
    private void loadSample() {
        setBusy(true);
        String pathPrefix = pathFilterField.getText() == null ? "" : pathFilterField.getText().trim();
        statusLabel.setText("Loading random unnamed faces...");
        facesPane.getChildren().clear();

        setTask(new Task<>() {
            @Override
            protected List<FaceRecord> call() throws SQLException {
                return namingService.findRandomUnnamed(BATCH_SIZE, pathPrefix);
            }
        });

        activeTask.setOnSucceeded(e -> {
            @SuppressWarnings("unchecked")
            List<FaceRecord> faces = (List<FaceRecord>) activeTask.getValue();
            showFaces(faces);
            setBusy(false);
            statusLabel.setText(faces.isEmpty()
                    ? (pathPrefix.isEmpty()
                            ? "No unnamed faces left — import photos or visit the other tagging tabs."
                            : "No unnamed faces match the path filter.")
                    : faces.size() + " random unnamed face(s). Select faces, type a name, and press Tag selected.");
        });

        activeTask.setOnFailed(e -> {
            setBusy(false);
            handleFailure("Could not load faces", activeTask.getException());
        });

        startTask("random-sample-loader");
    }

    /**
     * Checks the name typed into the field against existing names and shows
     * the outcome in the adjacent label. Runs on a background task and ignores
     * stale results once the field changes again.
     */
    private void checkNameExists() {
        if (nameField.isDisabled()) {
            return;
        }
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            nameExistsLabel.setText("");
            nameExistsLabel.setStyle("");
            return;
        }

        setTask(new Task<Optional<NameRecord>>() {
            @Override
            protected Optional<NameRecord> call() throws SQLException {
                return namingService.findName(name);
            }
        });

        activeTask.setOnSucceeded(e -> {
            String current = nameField.getText() == null ? "" : nameField.getText().trim();
            if (!name.equals(current)) {
                return; // user kept typing; ignore the stale result
            }
            @SuppressWarnings("unchecked")
            Optional<NameRecord> existing = (Optional<NameRecord>) activeTask.getValue();
            if (existing.isPresent()) {
                nameExistsLabel.setText("Name already exists ("
                        + existing.get().faceCount() + " face(s))");
                nameExistsLabel.setStyle("-fx-text-fill: #c9302c;");
            } else {
                nameExistsLabel.setText("New name");
                nameExistsLabel.setStyle("-fx-text-fill: #3c763d;");
            }
        });

        activeTask.setOnFailed(e -> {
            // name lookup failing should not block tagging
        });

        startTask("randomname-name-exists");
    }

    /**
     * Reloads a fresh sample. Called by the tab window when this tab is
     * selected, so faces tagged in other tabs are excluded next time.
     */
    @Override
    public void refresh() {
        loadSample();
    }

    /**
     * Renders the given faces as a selectable grid.
     *
     * @param faces the faces in the sample
     */
    private void showFaces(List<FaceRecord> faces) {
        facesPane.getChildren().clear();
        for (FaceRecord face : faces) {
            VBox card = new VBox(4);
            card.setAlignment(Pos.TOP_CENTER);
            card.setPadding(new Insets(4));
            card.setUserData(face.id());
            card.getStyleClass().add("candidate-card");

            ImageView thumb = new ImageView();
            setImage(thumb, face);
            thumb.setFitWidth(THUMBNAIL_SIZE);
            thumb.setFitHeight(THUMBNAIL_SIZE);
            thumb.setPreserveRatio(true);
            thumb.setSmooth(true);
            installPathTooltip(thumb, face);

            Label idLabel = new Label("id " + face.id());
            idLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #666666;");

            card.getChildren().addAll(thumb, idLabel);
            card.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    toggleSelected(card);
                }
            });
            installContextMenu(card, face);
            facesPane.getChildren().add(card);
        }
    }

    /**
     * Installs a right-click context menu on a face card with an "Open
     * Original" entry, grayed out while the source image is not available on
     * disk.
     *
     * @param card the card to attach the menu to
     * @param face the face whose source image should be openable
     */
    private void installContextMenu(VBox card, FaceRecord face) {
        card.setOnContextMenuRequested(e -> {
            MenuItem openOriginalItem = new MenuItem("Open Original");
            openOriginalItem.setDisable(!isOriginalAvailable(face));
            openOriginalItem.setOnAction(ev -> openOriginal(face.imageHash()));
            new ContextMenu(openOriginalItem).show(card, e.getScreenX(), e.getScreenY());
        });
    }

    /**
     * Tells whether the original file of a face's source image exists on disk.
     *
     * @param face the face whose source image to check
     * @return {@code true} if the original file is available
     */
    private boolean isOriginalAvailable(FaceRecord face) {
        try {
            return namingService.isOriginalAvailable(face.imageHash());
        } catch (SQLException ex) {
            statusLabel.setText("Could not check the original image.");
            return false;
        }
    }

    /**
     * Opens the original file of a face's source image in the default viewer.
     *
     * @param hash content hash of the source image
     */
    private void openOriginal(String hash) {
        try {
            boolean opened = namingService.openOriginal(hash);
            statusLabel.setText(opened ? "" : "Original file not found.");
        } catch (IOException | SQLException ex) {
            statusLabel.setText("Could not open the original image.");
            handleFailure("Could not open the original image", ex);
        }
    }

    /**
     * Toggles a card's selected look and keeps the Tag button enabled while
     * any face is selected.
     *
     * @param card the clicked card
     */
    private void toggleSelected(VBox card) {
        if (card.getStyleClass().contains("selected")) {
            card.getStyleClass().remove("selected");
        } else {
            card.getStyleClass().add("selected");
        }
        tagSelectedButton.setDisable(selectedFaceIds().isEmpty());
    }

    /**
     * Collects the face ids of the currently selected cards.
     *
     * @return the selected face ids
     */
    private List<Long> selectedFaceIds() {
        List<Long> ids = new ArrayList<>();
        for (javafx.scene.Node node : facesPane.getChildren()) {
            if (node.getStyleClass().contains("selected") && node.getUserData() instanceof Long id) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Tags all selected faces with the typed name, then removes them from the
     * current sample.
     */
    private void onTagSelected() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            statusLabel.setText("Type a name first.");
            return;
        }
        List<Long> faceIds = selectedFaceIds();
        if (faceIds.isEmpty()) {
            statusLabel.setText("Click faces to select them, then press Tag selected.");
            return;
        }

        setBusy(true);
        statusLabel.setText("Tagging " + faceIds.size() + " face(s) as '" + name + "'...");

        setTask(new Task<Void>() {
            @Override
            protected Void call() throws SQLException {
                long nameId = namingService.createOrFindName(name);
                for (Long faceId : faceIds) {
                    namingService.tagFace(faceId, nameId);
                }
                return null;
            }
        });

        activeTask.setOnSucceeded(e -> {
            facesPane.getChildren().removeIf(node ->
                    node.getUserData() instanceof Long id && faceIds.contains(id));
            setBusy(false);
            statusLabel.setText("Tagged " + faceIds.size() + " face(s) as '" + name + "'.");
        });

        activeTask.setOnFailed(e -> {
            setBusy(false);
            handleFailure("Could not tag faces", activeTask.getException());
        });

        startTask("random-tagger");
    }

    /**
     * Shows the face's JPEG thumbnail in the given view, or clears it when
     * the sub-image is unavailable.
     *
     * @param view the image view to update
     * @param face the face whose bytes should be shown
     */
    private static void setImage(ImageView view, FaceRecord face) {
        byte[] jpg = face != null ? face.subImageJpg() : null;
        if (jpg != null && jpg.length > 0) {
            view.setImage(new Image(new ByteArrayInputStream(jpg)));
        } else {
            view.setImage(null);
        }
    }

    /**
     * Installs a hover tooltip showing the on-disk paths of the face's source
     * image. The paths are looked up asynchronously on a daemon thread so the
     * UI stays responsive; the tooltip shows progress and fallback states.
     *
     * @param thumb the image view to attach the tooltip to
     * @param face  the face whose source image paths should be shown
     */
    private void installPathTooltip(ImageView thumb, FaceRecord face) {
        Tooltip tooltip = new Tooltip("Loading image path…");
        Tooltip.install(thumb, tooltip);
        Task<List<String>> lookup = new Task<>() {
            @Override
            protected List<String> call() throws SQLException {
                return namingService.findImagePaths(face.imageHash());
            }
        };
        lookup.setOnSucceeded(e -> {
            List<String> paths = lookup.getValue();
            tooltip.setText(paths.isEmpty()
                    ? "No stored path for this image"
                    : String.join(System.lineSeparator(), paths));
        });
        lookup.setOnFailed(e -> tooltip.setText("Image path unavailable"));
        Thread thread = new Thread(lookup, "randomname-path-tooltip-" + face.id());
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Enables or disables interaction while a background operation runs.
     *
     * @param busy {@code true} while a background operation runs
     */
    private void setBusy(boolean busy) {
        nextButton.setDisable(busy);
        pathFilterField.setDisable(busy);
        nameField.setDisable(busy);
        tagSelectedButton.setDisable(busy || selectedFaceIds().isEmpty());
    }

    /**
     * Replaces the active task, cancelling any previous one.
     *
     * @param task the new task
     */
    private void setTask(Task<?> task) {
        if (activeTask != null) {
            activeTask.cancel(true);
        }
        activeTask = task;
    }

    /**
     * Starts the active task on a daemon background thread.
     *
     * @param threadName the thread name
     */
    private void startTask(String threadName) {
        if (activeTask == null) {
            return;
        }
        Thread thread = new Thread(activeTask, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Raises a modal error dialog and resets the status bar.
     *
     * @param message the header text
     * @param error   the underlying exception
     */
    private void handleFailure(String message, Throwable error) {
        statusLabel.setText(message + ".");
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Tag random face");
        alert.setHeaderText(message);
        alert.setContentText(error.getMessage() == null ? error.toString() : error.getMessage());
        Window window = getScene() != null ? getScene().getWindow() : null;
        if (window != null) {
            alert.initOwner(window);
        }
        alert.showAndWait();
    }
}