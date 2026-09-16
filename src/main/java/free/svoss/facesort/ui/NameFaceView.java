package free.svoss.facesort.ui;

import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.SimilarityResult;
import free.svoss.facesort.service.ClusteringService;
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
import java.util.List;
import java.util.Locale;

/**
 * The "Put a name to a face" tab (IMPLEMENTATION_PLAN.md 6.4).
 *
 * <p>Clusters of unnamed faces are presented one at a time: the cluster
 * representative is shown large, the user types a name, and the remaining
 * cluster faces are re-ranked by similarity to the tagged face so the most
 * similar ones can be tagged in the same pass.</p>
 *
 * <p>The view owns no business logic; all clustering and tagging is
 * delegated to {@link NamingService}. Queries run on background
 * {@link Task}s so the UI stays responsive.</p>
 */
public class NameFaceView extends BorderPane implements Refreshable {

    private static final double REPRESENTATIVE_SIZE = 180.0;
    private static final double CANDIDATE_SIZE = 110.0;
    private static final int CANDIDATE_LIMIT = 24;

    private final NamingService namingService;

    private final Label statusLabel = new Label("");
    private final Label clusterLabel = new Label("");
    private final ImageView representativeView = new ImageView();
    private final TextField nameField = new TextField();
    private final Button tagButton = new Button("Tag");
    private final Button nextButton = new Button("Next cluster");
    private final FlowPane candidatesPane = new FlowPane(10, 10);

    private List<ClusteringService.Cluster> clusters;
    private int clusterIndex = -1;
    private Task<?> activeTask;

    /**
     * Creates the naming tab.
     *
     * @param namingService the naming service; must not be null
     */
    public NameFaceView(NamingService namingService) {
        this.namingService = namingService;
        buildUi();
        loadClusters();
    }

    /**
     * Builds the representative area, the name input and the candidates grid.
     */
    private void buildUi() {
        representativeView.setFitWidth(REPRESENTATIVE_SIZE);
        representativeView.setFitHeight(REPRESENTATIVE_SIZE);
        representativeView.setPreserveRatio(true);
        representativeView.setSmooth(true);
        representativeView.setStyle("-fx-border-color: #4a90d9; -fx-border-width: 2;");

        nameField.setPromptText("Enter a name");
        tagButton.setDefaultButton(true);
        tagButton.setOnAction(e -> onTagRepresentative());
        nextButton.setOnAction(e -> showNextCluster());

        HBox nameRow = new HBox(8, new Label("Name:"), nameField, tagButton, nextButton);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nameField, Priority.ALWAYS);

        VBox content = new VBox(10,
                clusterLabel,
                representativeView,
                nameRow,
                new Label("More faces from this cluster, most similar first:"),
                candidatesPane);
        content.setPadding(new Insets(10));
        content.setAlignment(Pos.TOP_CENTER);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);

        statusLabel.setWrapText(true);
        BorderPane.setMargin(statusLabel, new Insets(0, 10, 10, 10));

        setCenter(scroll);
        setBottom(statusLabel);
    }

    /**
     * Loads the clusters (largest first) in the background and shows the first.
     */
    private void loadClusters() {
        setBusy(true, true);
        statusLabel.setText("Clustering unnamed faces...");
        clusters = null;
        clusterIndex = -1;

        setTask(new Task<>() {
            @Override
            protected List<ClusteringService.Cluster> call() throws SQLException {
                return namingService.getClusters();
            }
        });

        activeTask.setOnSucceeded(e -> {
            @SuppressWarnings("unchecked")
            List<ClusteringService.Cluster> result =
                    (List<ClusteringService.Cluster>) activeTask.getValue();
            clusters = result;
            setBusy(false, clusters.isEmpty());
            if (clusters.isEmpty()) {
                clusterLabel.setText("No unnamed faces to name.");
            } else {
                showNextCluster();
            }
        });

        activeTask.setOnFailed(e -> {
            setBusy(false, true);
            handleFailure("Could not load clusters", activeTask.getException());
        });

        startTask("nameface-cluster-loader");
    }

    /**
     * Reloads the clusters of unnamed faces. Called by the tab window when
     * this tab is selected, so newly tagged faces are reflected. The walk
     * restarts at the largest cluster.
     */
    @Override
    public void refresh() {
        loadClusters();
    }

    /**
     * Moves to the next cluster, wrapping the representative into view.
     */
    private void showNextCluster() {
        if (clusters == null || clusters.isEmpty()) {
            return;
        }
        clusterIndex = (clusterIndex + 1) % clusters.size();
        ClusteringService.Cluster cluster = clusters.get(clusterIndex);

        clusterLabel.setText(String.format(Locale.ROOT,
                "Cluster %d of %d — %d face(s)",
                clusterIndex + 1, clusters.size(), cluster.faces().size()));
        setImage(representativeView, cluster.representative());
        nameField.clear();
        candidatesPane.getChildren().clear();
    }

    /**
     * Tags the current representative with the typed name and shows the most
     * similar remaining faces from the same cluster.
     */
    private void onTagRepresentative() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            statusLabel.setText("Type a name first.");
            return;
        }
        if (clusters == null || clusterIndex < 0) {
            return;
        }

        FaceRecord representative = clusters.get(clusterIndex).representative();
        setBusy(true, true);
        statusLabel.setText("Tagging and searching similar faces...");

        setTask(new Task<List<SimilarityResult>>() {
            @Override
            protected List<SimilarityResult> call() throws SQLException {
                long nameId = namingService.createOrFindName(name);
                namingService.tagFace(representative.id(), nameId);
                return namingService.findSimilarUnnamed(representative.id(), CANDIDATE_LIMIT);
            }
        });

        activeTask.setOnSucceeded(e -> {
            @SuppressWarnings("unchecked")
            List<SimilarityResult> similar =
                    (List<SimilarityResult>) activeTask.getValue();
            setBusy(false, clusters.isEmpty());
            statusLabel.setText("Tagged as '" + name + "'. " + similar.size()
                    + " similar face(s) suggested.");
            removeCluster(representative.id());
            showCandidates(similar);
        });

        activeTask.setOnFailed(e -> {
            setBusy(false, clusters == null || clusters.isEmpty());
            handleFailure("Could not tag face", activeTask.getException());
        });

        startTask("nameface-tagger");
    }

    /**
     * Renders the similar-face grid. Each candidate carries its face id in
     * the card's user data so tagging can remove the right card.
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
            card.setStyle("-fx-background-color: #f8f8fc; -fx-background-radius: 6;"
                    + " -fx-border-color: #ddd; -fx-border-radius: 6;");
            card.setUserData(face.id());

            ImageView thumb = new ImageView();
            setImage(thumb, face);
            thumb.setFitWidth(CANDIDATE_SIZE);
            thumb.setFitHeight(CANDIDATE_SIZE);
            thumb.setPreserveRatio(true);
            thumb.setSmooth(true);

            Label sim = new Label(String.format(Locale.ROOT, "%.0f%%",
                    candidate.similarity() * 100));
            sim.setStyle("-fx-font-size: 11; -fx-text-fill: #666666;");

            Button tag = new Button("Tag");
            tag.setOnAction(e -> onTagCandidate(face.id()));

            card.getChildren().addAll(thumb, sim, tag);
            candidatesPane.getChildren().add(card);
        }
    }

    /**
     * Tags a suggested candidate with the name typed for the representative.
     *
     * @param faceId id of the candidate face
     */
    private void onTagCandidate(long faceId) {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            statusLabel.setText("Type the name to use for the suggestions.");
            return;
        }
        setBusy(true, true);
        statusLabel.setText("Tagging face " + faceId + "...");

        setTask(new Task<Void>() {
            @Override
            protected Void call() throws SQLException {
                long nameId = namingService.createOrFindName(name);
                namingService.tagFace(faceId, nameId);
                return null;
            }
        });

        activeTask.setOnSucceeded(e -> {
            setBusy(false, clusters == null || clusters.isEmpty());
            statusLabel.setText("Tagged face " + faceId + " as '" + name + "'.");
            candidatesPane.getChildren().removeIf(node ->
                    node.getUserData() instanceof Long id && id == faceId);
        });

        activeTask.setOnFailed(e -> {
            setBusy(false, clusters == null || clusters.isEmpty());
            handleFailure("Could not tag face", activeTask.getException());
        });

        startTask("nameface-tag-candidate");
    }

    /**
     * Removes the just-tagged cluster from the rotation.
     *
     * @param faceId id of the tagged representative
     */
    private void removeCluster(long faceId) {
        clusters.removeIf(c -> c.representative().id() == faceId);
        if (clusters.isEmpty()) {
            clusterLabel.setText("All clusters named.");
        } else {
            clusterIndex--;
            showNextCluster();
        }
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
     * @param busy         {@code true} while a background operation runs
     * @param noClusters   whether the cluster rotation is empty
     */
    private void setBusy(boolean busy, boolean noClusters) {
        tagButton.setDisable(busy);
        nextButton.setDisable(busy || noClusters);
        nameField.setDisable(busy);
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
        alert.setTitle("Name Face");
        alert.setHeaderText(message);
        alert.setContentText(error.getMessage() == null ? error.toString() : error.getMessage());
        Window window = getScene() != null ? getScene().getWindow() : null;
        if (window != null) {
            alert.initOwner(window);
        }
        alert.showAndWait();
    }
}