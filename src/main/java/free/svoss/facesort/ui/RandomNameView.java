package free.svoss.facesort.ui;

import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.service.NamingService;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Random Tag" tab.
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

        HBox.setHgrow(nameField, Priority.ALWAYS);
        HBox controls = new HBox(8,
                new Label("Name:"), nameField, tagSelectedButton, nextButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(10));

        facesPane.setPadding(new Insets(10));

        ScrollPane scroll = new ScrollPane(facesPane);
        scroll.setFitToWidth(true);

        statusLabel.setWrapText(true);
        BorderPane.setMargin(statusLabel, new Insets(0, 10, 10, 10));

        setTop(controls);
        setCenter(scroll);
        setBottom(statusLabel);
    }

    /**
     * Loads a fresh random sample of unnamed faces in the background and
     * renders it as a grid.
     */
    private void loadSample() {
        setBusy(true);
        statusLabel.setText("Loading random unnamed faces...");
        facesPane.getChildren().clear();

        setTask(new Task<>() {
            @Override
            protected List<FaceRecord> call() throws SQLException {
                return namingService.findRandomUnnamed(BATCH_SIZE);
            }
        });

        activeTask.setOnSucceeded(e -> {
            @SuppressWarnings("unchecked")
            List<FaceRecord> faces = (List<FaceRecord>) activeTask.getValue();
            showFaces(faces);
            setBusy(false);
            statusLabel.setText(faces.isEmpty()
                    ? "No unnamed faces left — import photos or visit the other tagging tabs."
                    : faces.size() + " random unnamed face(s). Select faces, type a name, and press Tag selected.");
        });

        activeTask.setOnFailed(e -> {
            setBusy(false);
            handleFailure("Could not load faces", activeTask.getException());
        });

        startTask("random-sample-loader");
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

            Label idLabel = new Label("id " + face.id());
            idLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #666666;");

            card.getChildren().addAll(thumb, idLabel);
            card.setOnMouseClicked(e -> toggleSelected(card));
            facesPane.getChildren().add(card);
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
     * Enables or disables interaction while a background operation runs.
     *
     * @param busy {@code true} while a background operation runs
     */
    private void setBusy(boolean busy) {
        nextButton.setDisable(busy);
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
        alert.setTitle("Random Tag");
        alert.setHeaderText(message);
        alert.setContentText(error.getMessage() == null ? error.toString() : error.getMessage());
        Window window = getScene() != null ? getScene().getWindow() : null;
        if (window != null) {
            alert.initOwner(window);
        }
        alert.showAndWait();
    }
}