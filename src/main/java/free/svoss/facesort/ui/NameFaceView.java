package free.svoss.facesort.ui;

import free.svoss.facesort.i18n.I18n;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.model.SimilarityResult;
import free.svoss.facesort.service.ClusteringService;
import free.svoss.facesort.service.NamingService;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

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
    private final Label nameExistsLabel = new Label("");
    private final Button tagButton = new Button(I18n.get("ui.nameFace.tag"));
    private final Button nextButton = new Button(I18n.get("ui.nameFace.nextCluster"));
    private final FlowPane candidatesPane = new FlowPane(10, 10);

    private List<ClusteringService.Cluster> clusters;
    private int clusterIndex = -1;
    private final TaskRunner taskRunner = new TaskRunner();
    private long candidateRepId = -1;
    private final FaceUi.FaceActions faceActions;

    /**
     * Creates the naming tab.
     *
     * @param namingService the naming service; must not be null
     */
    public NameFaceView(NamingService namingService) {
        this.namingService = namingService;
        this.faceActions = new FaceUi.FaceActions(
                new FaceUi.FaceActions.Source() {
                    @Override
                    public boolean isOriginalAvailable(String hash) throws SQLException {
                        return namingService.isOriginalAvailable(hash);
                    }

                    @Override
                    public boolean isContainingFolderAvailable(String hash) throws SQLException {
                        return namingService.isContainingFolderAvailable(hash);
                    }

                    @Override
                    public boolean openOriginal(String hash) throws java.io.IOException, SQLException {
                        return namingService.openOriginal(hash);
                    }

                    @Override
                    public boolean openContainingFolder(String hash) throws java.io.IOException, SQLException {
                        return namingService.openContainingFolder(hash);
                    }
                },
                statusLabel::setText,
                (hash, ex) -> statusLabel.setText(I18n.get("common.originalCheckFailed")),
                (message, ex) -> {
                    statusLabel.setText(message);
                    handleFailure(message, ex);
                });
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

        nameField.setPromptText(I18n.get("ui.nameFace.namePrompt"));
        tagButton.setDefaultButton(true);
        tagButton.setOnAction(e -> onTagRepresentative());
        nextButton.setOnAction(e -> showNextCluster());

        HBox nameRow = new HBox(8, new Label(I18n.get("ui.nameFace.name")), nameField, nameExistsLabel, tagButton, nextButton);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nameField, Priority.ALWAYS);
        nameExistsLabel.setWrapText(true);
        nameField.textProperty().addListener((obs, oldText, newText) -> checkNameExists());

        VBox content = new VBox(10,
                clusterLabel,
                representativeView,
                nameRow,
                new Label(I18n.get("ui.nameFace.moreFaces")),
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
        statusLabel.setText(I18n.get("ui.nameFace.clustering"));
        clusters = null;
        clusterIndex = -1;

        Task<List<ClusteringService.Cluster>> task = new Task<>() {
            @Override
            protected List<ClusteringService.Cluster> call() throws SQLException {
                return namingService.getClusters();
            }
        };

        task.setOnSucceeded(e -> {
            List<ClusteringService.Cluster> result = task.getValue();
            clusters = result;
            setBusy(false, clusters.isEmpty());
            if (clusters.isEmpty()) {
                clusterLabel.setText(I18n.get("ui.nameFace.noUnnamed"));
            } else {
                showNextCluster();
            }
        });

        task.setOnFailed(e -> {
            setBusy(false, true);
            handleFailure(I18n.get("ui.nameFace.clusterFailed"), task.getException());
        });

        taskRunner.start(task, "nameface-cluster-loader");
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
     * Moves to the next cluster, wrapping the representative into view and
     * previewing the cluster's other faces.
     */
    private void showNextCluster() {
        showNextCluster(true);
    }

    /**
     * Moves to the next cluster, wrapping the representative into view.
     *
     * @param loadCandidates whether to preview the cluster's other faces in the
     *                       candidates grid
     */
    private void showNextCluster(boolean loadCandidates) {
        if (clusters == null || clusters.isEmpty()) {
            return;
        }
        clusterIndex = (clusterIndex + 1) % clusters.size();
        ClusteringService.Cluster cluster = clusters.get(clusterIndex);

        clusterLabel.setText(I18n.format("ui.nameFace.clusterCount",
                clusterIndex + 1, clusters.size(), cluster.faces().size()));
        FaceUi.setImage(representativeView, cluster.representative());
        FaceUi.installPathTooltip(representativeView, cluster.representative(),
                "nameface-path-tooltip-", namingService::findImagePaths);
        faceActions.installFaceMenu(representativeView, cluster.representative());
        nameField.clear();
        candidatesPane.getChildren().clear();
        if (loadCandidates) {
            loadClusterCandidates(cluster);
        }
    }

    /**
     * Populates the candidates grid with the current cluster's other faces,
     * most similar to the representative first, so the whole cluster is visible
     * before the user types a name.
     *
     * @param cluster the cluster being displayed
     */
    private void loadClusterCandidates(ClusteringService.Cluster cluster) {
        FaceRecord representative = cluster.representative();
        candidateRepId = representative.id();
        List<FaceRecord> members = cluster.faces().stream()
                .filter(face -> face.id() != representative.id())
                .toList();
        if (members.isEmpty()) {
            return;
        }

        Task<List<SimilarityResult>> task = new Task<>() {
            @Override
            protected List<SimilarityResult> call() {
                return namingService.rankSimilar(representative, members, CANDIDATE_LIMIT);
            }
        };

        task.setOnSucceeded(e -> {
            List<SimilarityResult> similar = task.getValue();
            if (candidateRepId == representative.id()) {
                showCandidates(similar);
            }
        });

        task.setOnFailed(e -> {
            if (candidateRepId == representative.id()) {
                handleFailure(I18n.get("ui.nameFace.rankFailed"), task.getException());
            }
        });

        taskRunner.start(task, "nameface-cluster-candidates");
    }

    /**
     * Checks the name typed into the field against existing names and shows
     * the outcome in the adjacent label. Runs on a background task and ignores
     * stale results once the field changes again.
     */
    private void checkNameExists() {
        FaceUi.checkNameExistence(nameField, nameExistsLabel, taskRunner,
                "nameface-name-exists",
                "ui.nameFace.nameExists", "ui.nameFace.newName",
                namingService::findName);
    }

    /**
     * Tags the current representative with the typed name and shows the most
     * similar remaining faces from the same cluster.
     */
    private void onTagRepresentative() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            statusLabel.setText(I18n.get("ui.nameFace.typeNameFirst"));
            return;
        }
        if (clusters == null || clusterIndex < 0) {
            return;
        }

        FaceRecord representative = clusters.get(clusterIndex).representative();
        setBusy(true, true);
        statusLabel.setText(I18n.get("ui.nameFace.taggingSearching"));

        Task<List<SimilarityResult>> task = new Task<>() {
            @Override
            protected List<SimilarityResult> call() throws SQLException {
                long nameId = namingService.createOrFindName(name);
                namingService.tagFace(representative.id(), nameId);
                return namingService.findSimilarUnnamed(representative.id(), CANDIDATE_LIMIT);
            }
        };

        task.setOnSucceeded(e -> {
            List<SimilarityResult> similar = task.getValue();
            setBusy(false, clusters.isEmpty());
            statusLabel.setText(I18n.format("ui.nameFace.taggedSuggest",
                    name, similar.size()));
            removeCluster(representative.id());
            candidateRepId = -1;
            showCandidates(similar);
        });

        task.setOnFailed(e -> {
            setBusy(false, clusters == null || clusters.isEmpty());
            handleFailure(I18n.get("ui.nameFace.tagFailed"), task.getException());
        });

        taskRunner.start(task, "nameface-tagger");
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
            VBox card = FaceUi.faceCard(face, CANDIDATE_SIZE);

            Label sim = new Label(I18n.percent(candidate.similarity()));
            sim.setStyle("-fx-font-size: 11; -fx-text-fill: #666666;");

            Button tag = new Button(I18n.get("ui.nameFace.tag"));
            tag.setOnAction(e -> onTagCandidate(face.id()));

            card.getChildren().addAll(sim, tag);
            faceActions.installFaceMenu(card, face);
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
            statusLabel.setText(I18n.get("ui.nameFace.typeSuggestName"));
            return;
        }
        setBusy(true, true);
        statusLabel.setText(I18n.format("ui.nameFace.taggingFace", faceId));

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws SQLException {
                long nameId = namingService.createOrFindName(name);
                namingService.tagFace(faceId, nameId);
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            setBusy(false, clusters == null || clusters.isEmpty());
            statusLabel.setText(I18n.format("ui.nameFace.taggedFace", faceId, name));
            candidatesPane.getChildren().removeIf(node ->
                    node.getUserData() instanceof Long id && id == faceId);
        });

        task.setOnFailed(e -> {
            setBusy(false, clusters == null || clusters.isEmpty());
            handleFailure(I18n.get("ui.nameFace.tagFailed"), task.getException());
        });

        taskRunner.start(task, "nameface-tag-candidate");
    }

    /**
     * Removes the just-tagged cluster from the rotation.
     *
     * @param faceId id of the tagged representative
     */
    private void removeCluster(long faceId) {
        clusters.removeIf(c -> c.representative().id() == faceId);
        if (clusters.isEmpty()) {
            clusterLabel.setText(I18n.get("ui.nameFace.allNamed"));
        } else {
            clusterIndex--;
            showNextCluster(false);
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
     * Raises a modal error dialog and resets the status bar.
     *
     * @param message the header text
     * @param error   the underlying exception
     */
    private void handleFailure(String message, Throwable error) {
        statusLabel.setText(message + ".");
        Window window = getScene() != null ? getScene().getWindow() : null;
        FaceUi.showError(window, "ui.nameFace.alertTitle", message, error);
    }
}