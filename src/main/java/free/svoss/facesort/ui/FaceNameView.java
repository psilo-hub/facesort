package free.svoss.facesort.ui;

import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.model.SimilarityResult;
import free.svoss.facesort.service.FaceToNameService;

import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "Put a face to a name" tab (IMPLEMENTATION_PLAN.md 6.5).
 *
 * <p>Given a name selected on the left, all unnamed faces are ranked by
 * similarity to the average embedding of the faces already tagged with that
 * name and shown as a grid on the right. The user can select several
 * candidates and tag them all with the selected name.</p>
 *
 * <p>The view owns no business logic; all ranking and tagging is delegated to
 * {@link FaceToNameService}. Queries run on background {@link Task}s so the
 * UI stays responsive.</p>
 */
public class FaceNameView extends BorderPane implements Refreshable {

    private static final double THUMBNAIL_SIZE = 110.0;
    private static final int CANDIDATE_LIMIT = 30;

    private final FaceToNameService faceToNameService;

    private final Label statusLabel = new Label("");
    private final ListView<NameRecord> nameList = new ListView<>();
    private final FlowPane candidatesPane = new FlowPane(10, 10);
    private final Button tagSelectedButton = new Button("Tag selected");

    private NameRecord activeName;
    private Task<?> activeTask;

    /**
     * Creates the face-to-name tab.
     *
     * @param faceToNameService the face-to-name service; must not be null
     */
    public FaceNameView(FaceToNameService faceToNameService) {
        this.faceToNameService = faceToNameService;
        buildUi();
        loadNames(false);
    }

    /**
     * Builds the name list on the left and the candidates grid on the right.
     */
    private void buildUi() {
        nameList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        nameList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(NameRecord name, boolean empty) {
                super.updateItem(name, empty);
                if (empty || name == null) {
                    setText(null);
                } else if (name.faceCount() <= 0) {
                    setText(name.name());
                } else {
                    setText(name.name() + " (" + name.faceCount() + ")");
                }
            }
        });
        nameList.setPrefWidth(220);
        nameList.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldName, newName) -> {
                    if (newName != null) {
                        selectName(newName);
                    }
                });

        tagSelectedButton.setDisable(true);
        tagSelectedButton.setOnAction(e -> onTagSelected());

        VBox left = new VBox(6,
                new Label("Names:"),
                nameList,
                tagSelectedButton);
        left.setPadding(new Insets(10));
        VBox.setVgrow(nameList, Priority.ALWAYS);

        candidatesPane.setPadding(new Insets(10));

        ScrollPane scroll = new ScrollPane(candidatesPane);
        scroll.setFitToWidth(true);

        statusLabel.setWrapText(true);
        BorderPane.setMargin(statusLabel, new Insets(0, 10, 10, 10));

        setLeft(left);
        setCenter(scroll);
        setBottom(statusLabel);
    }

    /**
     * Loads all names into the list in the background.
     *
     * @param refreshActiveName whether to reload the candidates for the
     *                          currently active name afterwards (used by
     *                          {@link #refresh()} when a tab switch may have
     *                          made the cached face counts stale)
     */
    private void loadNames(boolean refreshActiveName) {
        statusLabel.setText("Loading names...");

        setTask(new Task<List<NameRecord>>() {
            @Override
            protected List<NameRecord> call() throws SQLException {
                return faceToNameService.getAllNames();
            }
        });

        activeTask.setOnSucceeded(e -> {
            @SuppressWarnings("unchecked")
            List<NameRecord> names = (List<NameRecord>) activeTask.getValue();
            applyNames(names);
            if (refreshActiveName && activeName != null) {
                selectName(activeName); // reload candidates for the still-active name
            }
        });

        activeTask.setOnFailed(e ->
                handleFailure("Could not load names", activeTask.getException()));

        startTask("facename-name-loader");
    }

    /**
     * Applies the loaded names to the list and updates the status text.
     *
     * @param names the names loaded from the database
     */
    private void applyNames(List<NameRecord> names) {
        nameList.setItems(FXCollections.observableArrayList(names));
        statusLabel.setText(names.isEmpty()
                ? "No names yet — import photos and tag some faces first."
                : "Select a name to see similar unnamed faces.");
    }

    /**
     * Reloads the name list and, when a name is currently active, the
     * candidate faces for it. Called by the tab window when this tab is
     * selected.
     */
    @Override
    public void refresh() {
        loadNames(true);
    }

    /**
     * Loads the most similar unnamed faces for the selected name.
     *
     * @param name the selected name record
     */
    private void selectName(NameRecord name) {
        activeName = name;
        statusLabel.setText("Loading similar faces for '" + name.name() + "'...");
        candidatesPane.getChildren().clear();
        tagSelectedButton.setDisable(true);

        setTask(new Task<List<SimilarityResult>>() {
            @Override
            protected List<SimilarityResult> call() throws SQLException {
                return faceToNameService.findUnnamedForName(name.id(), CANDIDATE_LIMIT);
            }
        });

        activeTask.setOnSucceeded(e -> {
            @SuppressWarnings("unchecked")
            List<SimilarityResult> similar =
                    (List<SimilarityResult>) activeTask.getValue();
            showCandidates(similar);
            statusLabel.setText(similar.isEmpty()
                    ? "No unnamed faces similar to '" + name.name() + "'."
                    : similar.size() + " similar unnamed face(s) for '" + name.name() + "'.");
        });

        activeTask.setOnFailed(e ->
                handleFailure("Could not load similar faces", activeTask.getException()));

        startTask("facename-similar-loader");
    }

    /**
     * Renders the candidates grid. Each card carries the face id in its user
     * data and is selected with a click before tagging.
     *
     * @param similar the ranked candidates
     */
    private void showCandidates(List<SimilarityResult> similar) {
        candidatesPane.getChildren().clear();
        for (SimilarityResult candidate : similar) {
            FaceRecord face = candidate.faceRecord();
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

            Label sim = new Label(String.format(Locale.ROOT, "%.0f%%",
                    candidate.similarity() * 100));
            sim.setStyle("-fx-font-size: 11; -fx-text-fill: #666666;");

            card.getChildren().addAll(thumb, sim);
            card.setOnMouseClicked(e -> toggleSelected(card));
            candidatesPane.getChildren().add(card);
        }
    }

    /**
     * Toggles a candidate card's selected look and keeps the Tag button
     * enabled while any candidate is selected.
     *
     * @param card the clicked candidate card
     */
    private void toggleSelected(VBox card) {
        boolean selected = card.getStyleClass().contains("selected");
        if (selected) {
            card.getStyleClass().remove("selected");
        } else {
            card.getStyleClass().add("selected");
        }
        tagSelectedButton.setDisable(selectedCandidates().isEmpty());
    }

    /**
     * Collects the face ids of the currently selected candidate cards.
     *
     * @return the selected face ids
     */
    private List<Long> selectedCandidates() {
        List<Long> ids = new ArrayList<>();
        for (javafx.scene.Node node : candidatesPane.getChildren()) {
            if (node.getStyleClass().contains("selected") && node.getUserData() instanceof Long id) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Tags all selected candidates with the active name.
     */
    private void onTagSelected() {
        if (activeName == null) {
            return;
        }
        List<Long> faceIds = selectedCandidates();
        if (faceIds.isEmpty()) {
            statusLabel.setText("Click faces to select them, then press Tag selected.");
            return;
        }

        statusLabel.setText("Tagging " + faceIds.size() + " face(s) as '" + activeName.name() + "'...");
        setTask(new Task<Void>() {
            @Override
            protected Void call() throws SQLException {
                faceToNameService.tagFaces(faceIds, activeName.id());
                return null;
            }
        });

        activeTask.setOnSucceeded(e -> {
            statusLabel.setText("Tagged " + faceIds.size() + " face(s) as '"
                    + activeName.name() + "'.");
            selectName(activeName); // refresh: tagged faces disappear
        });

        activeTask.setOnFailed(e ->
                handleFailure("Could not tag faces", activeTask.getException()));

        startTask("facename-tagger");
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
        alert.setTitle("Face Name");
        alert.setHeaderText(message);
        alert.setContentText(error.getMessage() == null ? error.toString() : error.getMessage());
        Window window = getScene() != null ? getScene().getWindow() : null;
        if (window != null) {
            alert.initOwner(window);
        }
        alert.showAndWait();
    }
}