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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
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
        setImage(representativeView, cluster.representative());
        installPathTooltip(representativeView, cluster.representative());
        installContextMenu(representativeView, cluster.representative());
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
        if (nameField.isDisabled()) {
            return;
        }
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            nameExistsLabel.setText("");
            nameExistsLabel.setStyle("");
            return;
        }

        Task<Optional<NameRecord>> task = new Task<>() {
            @Override
            protected Optional<NameRecord> call() throws SQLException {
                return namingService.findName(name);
            }
        };

        task.setOnSucceeded(e -> {
            String current = nameField.getText() == null ? "" : nameField.getText().trim();
            if (!name.equals(current)) {
                return; // user kept typing; ignore the stale result
            }
            Optional<NameRecord> existing = task.getValue();
            if (existing.isPresent()) {
                nameExistsLabel.setText(I18n.format("ui.nameFace.nameExists",
                        existing.get().faceCount()));
                nameExistsLabel.setStyle("-fx-text-fill: #c9302c;");
            } else {
                nameExistsLabel.setText(I18n.get("ui.nameFace.newName"));
                nameExistsLabel.setStyle("-fx-text-fill: #3c763d;");
            }
        });

        task.setOnFailed(e -> {
            // name lookup failing should not block tagging
        });

        taskRunner.start(task, "nameface-name-exists");
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
            installPathTooltip(thumb, face);

            Label sim = new Label(String.format(Locale.ROOT, "%.0f%%",
                    candidate.similarity() * 100));
            sim.setStyle("-fx-font-size: 11; -fx-text-fill: #666666;");

            Button tag = new Button(I18n.get("ui.nameFace.tag"));
            tag.setOnAction(e -> onTagCandidate(face.id()));

            card.getChildren().addAll(thumb, sim, tag);
            installContextMenu(card, face);
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
        Tooltip tooltip = new Tooltip(I18n.get("common.loadingPath"));
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
                    ? I18n.get("common.noStoredPath")
                    : String.join(System.lineSeparator(), paths));
        });
        lookup.setOnFailed(e -> tooltip.setText(I18n.get("common.pathUnavailable")));
        Thread thread = new Thread(lookup, "nameface-path-tooltip-" + face.id());
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Installs a right-click context menu on a face thumbnail with "Open
     * Original" and "Open containing folder" entries, grayed out while the
     * source image is not available on disk.
     *
     * @param node the node to attach the menu to
     * @param face the face whose source image should be openable
     */
    private void installContextMenu(javafx.scene.Node node, FaceRecord face) {
        node.setOnContextMenuRequested(e -> {
            MenuItem openOriginalItem = new MenuItem(I18n.get("common.openOriginal"));
            openOriginalItem.setDisable(!isOriginalAvailable(face));
            openOriginalItem.setOnAction(ev -> openOriginal(face.imageHash()));
            MenuItem openContainingFolderItem = new MenuItem(I18n.get("common.openContainingFolder"));
            openContainingFolderItem.setDisable(!isContainingFolderAvailable(face));
            openContainingFolderItem.setOnAction(ev -> openContainingFolder(face.imageHash()));
            new ContextMenu(openOriginalItem, openContainingFolderItem)
                    .show(node, e.getScreenX(), e.getScreenY());
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
            statusLabel.setText(I18n.get("common.originalCheckFailed"));
            return false;
        }
    }

    /**
     * Tells whether the folder containing the original file of a face's source
     * image exists on disk.
     *
     * @param face the face whose containing folder to check
     * @return {@code true} if the containing folder is available
     */
    private boolean isContainingFolderAvailable(FaceRecord face) {
        try {
            return namingService.isContainingFolderAvailable(face.imageHash());
        } catch (SQLException ex) {
            statusLabel.setText(I18n.get("common.originalCheckFailed"));
            return false;
        }
    }

    /**
     * Opens the folder containing the original file of a face's source image in
     * the file manager.
     *
     * @param hash content hash of the source image
     */
    private void openContainingFolder(String hash) {
        try {
            boolean opened = namingService.openContainingFolder(hash);
            statusLabel.setText(opened ? "" : I18n.get("common.originalNotFound"));
        } catch (IOException | SQLException ex) {
            statusLabel.setText(I18n.get("common.openContainingFolderFailed"));
            handleFailure(I18n.get("common.openContainingFolderFailed"), ex);
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
            statusLabel.setText(opened ? "" : I18n.get("common.originalNotFound"));
        } catch (IOException | SQLException ex) {
            statusLabel.setText(I18n.get("common.openOriginalFailed"));
            handleFailure(I18n.get("common.openOriginalFailed"), ex);
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
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(I18n.get("ui.nameFace.alertTitle"));
        alert.setHeaderText(message);
        alert.setContentText(error.getMessage() == null ? error.toString() : error.getMessage());
        Window window = getScene() != null ? getScene().getWindow() : null;
        if (window != null) {
            alert.initOwner(window);
        }
        alert.showAndWait();
    }
}