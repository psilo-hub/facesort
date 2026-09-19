package free.svoss.facesort.ui;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.i18n.I18n;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextInputDialog;
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
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
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
 * candidates (Shift-clicking selects a whole range at once) and tag them all
 * with the selected name.</p>
 *
 * <p>The view owns no business logic; all ranking and tagging is delegated to
 * {@link FaceToNameService}. Queries run on background {@link Task}s so the
 * UI stays responsive.</p>
 */
public class FaceNameView extends BorderPane implements Refreshable {

    private static final double THUMBNAIL_SIZE = 110.0;
    private static final int NAMED_LIMIT = 5;

    private final FaceToNameService faceToNameService;
    private final ConfigModel config;

    private final Label statusLabel = new Label("");
    private final ListView<NameRecord> nameList = new ListView<>();
    private final Label namedFacesLabel = new Label("");
    private final FlowPane namedFacesPane = new FlowPane(10, 10);
    private final Label unnamedLabel = new Label(I18n.get("ui.faceName.unnamedLabel"));
    private final CheckBox excludeOtherNamesBox =
            new CheckBox(I18n.get("ui.faceName.excludeOtherNames"));
    private final TextField pathFilterField = new TextField();
    private final FlowPane candidatesPane = new FlowPane(10, 10);
    private final Button tagSelectedButton = new Button(I18n.get("ui.faceName.tagSelected"));
    private final Button renameButton = new Button(I18n.get("ui.faceName.rename"));
    private final Button exportButton = new Button(I18n.get("ui.faceName.export"));

    private NameRecord activeName;
    private Task<?> activeTask;
    private VBox rangeAnchor;

    /**
     * Creates the face-to-name tab.
     *
     * @param faceToNameService the face-to-name service; must not be null
     * @param config            the application settings; must not be null
     */
    public FaceNameView(FaceToNameService faceToNameService, ConfigModel config) {
        this.faceToNameService = faceToNameService;
        this.config = config;
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

        renameButton.setOnAction(e -> onRename());
        renameButton.disableProperty().bind(
                nameList.getSelectionModel().selectedItemProperty().isNull());
        renameButton.setTooltip(tooltip(I18n.get("ui.faceName.renameTooltip")));

        exportButton.setOnAction(e -> onExport());
        exportButton.disableProperty().bind(
                nameList.getSelectionModel().selectedItemProperty().isNull());
        exportButton.setTooltip(tooltip(I18n.get("ui.faceName.exportTooltip")));

        Tooltip exclusionTip = new Tooltip(I18n.get("ui.faceName.excludeTooltip"));
        exclusionTip.setWrapText(true);
        exclusionTip.setMaxWidth(420);
        excludeOtherNamesBox.setTooltip(exclusionTip);
        excludeOtherNamesBox.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            if (activeName != null) {
                selectName(activeName);
            }
        });

        VBox left = new VBox(6,
                new Label(I18n.get("ui.faceName.names")),
                nameList,
                tagSelectedButton,
                renameButton,
                exportButton);
        left.setPadding(new Insets(10));
        VBox.setVgrow(nameList, Priority.ALWAYS);

        unnamedLabel.setTooltip(tooltip(I18n.get("ui.faceName.selectionHint")));
        pathFilterField.setTooltip(tooltip(I18n.get("ui.faceName.pathFilterTooltip")));
        pathFilterField.setOnAction(e -> {
            if (activeName != null) {
                selectName(activeName);
            }
        });
        HBox.setHgrow(pathFilterField, Priority.ALWAYS);
        HBox filterBar = new HBox(6, new Label(I18n.get("ui.faceName.pathFilter")), pathFilterField);

        VBox center = new VBox(8,
                namedFacesLabel,
                namedFacesPane,
                unnamedLabel,
                excludeOtherNamesBox,
                filterBar,
                candidatesPane);
        center.setPadding(new Insets(10));

        ScrollPane scroll = new ScrollPane(center);
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
        statusLabel.setText(I18n.get("ui.faceName.loadingNames"));

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
                handleFailure(I18n.get("ui.faceName.loadNamesFailed"), activeTask.getException()));

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
                ? I18n.get("ui.faceName.noNames")
                : I18n.get("ui.faceName.selectName"));
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
     * Loads the faces already tagged with the selected name (most similar to
     * the average first) together with the most similar unnamed faces.
     *
     * @param name the selected name record
     */
    private void selectName(NameRecord name) {
        activeName = name;
        String pathPrefix = pathFilterField.getText() == null ? "" : pathFilterField.getText().trim();
        statusLabel.setText(I18n.format("ui.faceName.loadingSimilar", name.name()));
        namedFacesLabel.setText("");
        namedFacesPane.getChildren().clear();
        candidatesPane.getChildren().clear();
        tagSelectedButton.setDisable(true);

        setTask(new Task<NameContent>() {
            @Override
            protected NameContent call() throws SQLException {
                List<SimilarityResult> named =
                        faceToNameService.findMostSimilarNamed(name.id(), NAMED_LIMIT);
                List<SimilarityResult> candidates =
                        faceToNameService.findUnnamedForName(
                                name.id(), config.getFaceNameMaxImages(),
                                excludeOtherNamesBox.isSelected(), pathPrefix);
                return new NameContent(named, candidates);
            }
        });

        activeTask.setOnSucceeded(e -> {
            @SuppressWarnings("unchecked")
            NameContent content = (NameContent) activeTask.getValue();
            showNamedFaces(name, content.namedFaces());
            showCandidates(content.candidates());
            statusLabel.setText(content.candidates().isEmpty()
                    ? (pathPrefix.isEmpty()
                            ? I18n.format("ui.faceName.noneSimilar", name.name())
                            : I18n.format("ui.faceName.noneSimilarFilter", name.name()))
                    : I18n.format("ui.faceName.similarCount",
                            content.candidates().size(), name.name()));
        });

        activeTask.setOnFailed(e ->
                handleFailure(I18n.get("ui.faceName.similarLoadFailed"), activeTask.getException()));

        startTask("facename-similar-loader");
    }

    /**
     * Renders the faces already tagged with the selected name, most similar to
     * the name's average embedding first. Shows up to {@link #NAMED_LIMIT}.
     *
     * @param name  the selected name record
     * @param named the ranked tagged faces
     */
    private void showNamedFaces(NameRecord name, List<SimilarityResult> named) {
        namedFacesPane.getChildren().clear();
        if (named.isEmpty()) {
            namedFacesLabel.setText(I18n.format("ui.faceName.noneTagged", name.name()));
            return;
        }
        namedFacesLabel.setText(I18n.format("ui.faceName.taggedWith", name.name()));
        renderCards(namedFacesPane, named, false);
    }

    /**
     * Renders the candidates grid. Each card carries the face id in its user
     * data and is selected with a click before tagging.
     *
     * @param similar the ranked candidates
     */
    private void showCandidates(List<SimilarityResult> similar) {
        candidatesPane.getChildren().clear();
        rangeAnchor = null;
        renderCards(candidatesPane, similar, true);
    }

    /**
     * Renders similarity-ranked face cards into the given pane. When
     * {@code selectable} is true each card toggles its selected look so the
     * user can batch-tag.
     *
     * @param pane       the pane to populate
     * @param results    the ranked faces
     * @param selectable whether cards can be selected for tagging
     */
    private void renderCards(FlowPane pane, List<SimilarityResult> results, boolean selectable) {
        for (SimilarityResult candidate : results) {
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
            if (selectable) {
                card.setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.PRIMARY) {
                        if (e.isShiftDown() && rangeAnchor != null
                                && candidatesPane.getChildren().contains(rangeAnchor)) {
                            selectRange(rangeAnchor, card);
                        } else {
                            toggleSelected(card);
                            rangeAnchor = card;
                        }
                    }
                });
            }
            installContextMenu(card, face);
            pane.getChildren().add(card);
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
            MenuItem openOriginalItem = new MenuItem(I18n.get("common.openOriginal"));
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
            return faceToNameService.isOriginalAvailable(face.imageHash());
        } catch (SQLException ex) {
            statusLabel.setText(I18n.get("common.originalCheckFailed"));
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
            boolean opened = faceToNameService.openOriginal(hash);
            statusLabel.setText(opened ? "" : I18n.get("common.originalNotFound"));
        } catch (IOException | SQLException ex) {
            statusLabel.setText(I18n.get("common.openOriginalFailed"));
            handleFailure(I18n.get("common.openOriginalFailed"), ex);
        }
    }

    /**
     * Toggles a candidate card's selected look and keeps the Tag button
     * enabled while any candidate is selected.
     *
     * @param card the clicked candidate card
     */
    private void toggleSelected(VBox card) {
        setSelected(card, !card.getStyleClass().contains("selected"));
        tagSelectedButton.setDisable(selectedCandidates().isEmpty());
    }

    /**
     * Selects the whole range of candidate cards between the anchor and the
     * shift-clicked card, replacing the previous selection.
     *
     * @param anchorCard the card that fixes the start of the range
     * @param clickedCard the shift-clicked card that fixes the end
     */
    private void selectRange(VBox anchorCard, VBox clickedCard) {
        List<javafx.scene.Node> children = candidatesPane.getChildren();
        int size = children.size();
        List<Integer> indices = rangeSelection(
                children.indexOf(anchorCard), children.indexOf(clickedCard), size);
        if (indices.isEmpty()) {
            return;
        }
        java.util.Set<Integer> range = new java.util.HashSet<>(indices);
        for (int i = 0; i < size; i++) {
            setSelected((VBox) children.get(i), range.contains(i));
        }
        tagSelectedButton.setDisable(selectedCandidates().isEmpty());
    }

    /**
     * Puts or removes the {@code selected} style class on a candidate card.
     *
     * @param card     the card to update
     * @param selected whether the card should look selected
     */
    private static void setSelected(VBox card, boolean selected) {
        if (selected) {
            if (!card.getStyleClass().contains("selected")) {
                card.getStyleClass().add("selected");
            }
        } else {
            card.getStyleClass().remove("selected");
        }
    }

    /**
     * Computes the inclusive range of candidate indices between the range
     * anchor and the shift-clicked card, in ascending display order.
     *
     * @param anchorIndex  index of the anchor card
     * @param clickedIndex index of the shift-clicked card
     * @param size         number of candidate cards currently shown
     * @return the ascending, inclusive indices between the two cards,
     *         clamped to {@code [0, size)}; empty when {@code size <= 0}
     */
    static List<Integer> rangeSelection(int anchorIndex, int clickedIndex, int size) {
        if (size <= 0) {
            return List.of();
        }
        int anchor = Math.max(0, Math.min(anchorIndex, size - 1));
        int clicked = Math.max(0, Math.min(clickedIndex, size - 1));
        int low = Math.min(anchor, clicked);
        int high = Math.max(anchor, clicked);
        List<Integer> indices = new ArrayList<>(high - low + 1);
        for (int i = low; i <= high; i++) {
            indices.add(i);
        }
        return indices;
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
            statusLabel.setText(I18n.get("ui.faceName.clickToSelect"));
            return;
        }

        statusLabel.setText(I18n.format("ui.faceName.tagging", faceIds.size(), activeName.name()));
        setTask(new Task<Void>() {
            @Override
            protected Void call() throws SQLException {
                faceToNameService.tagFaces(faceIds, activeName.id());
                return null;
            }
        });

        activeTask.setOnSucceeded(e -> {
            statusLabel.setText(I18n.format("ui.faceName.tagged", faceIds.size(), activeName.name()));
            selectName(activeName); // refresh: tagged faces disappear
        });

        activeTask.setOnFailed(e ->
                handleFailure(I18n.get("ui.faceName.tagFailed"), activeTask.getException()));

        startTask("facename-tagger");
    }

    /**
     * Offers to rename the currently selected name. The rename runs on a
     * background task and, on success, reloads the name list and the candidates
     * for the (now renamed) active name.
     */
    private void onRename() {
        if (activeName == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog(activeName.name());
        dialog.setTitle(I18n.get("ui.faceName.rename.title"));
        dialog.setHeaderText(I18n.format("ui.faceName.rename.header", activeName.name()));
        dialog.setContentText(I18n.get("ui.faceName.rename.newName"));
        Button okButton = (Button) dialog.getDialogPane().lookupButton(
                javafx.scene.control.ButtonType.OK);
        okButton.setText(I18n.get("ui.faceName.rename.confirm"));

        if (!dialog.showAndWait().isPresent()) {
            return;
        }
        String newName = dialog.getEditor().getText().trim();
        if (newName.isEmpty()) {
            statusLabel.setText(I18n.get("ui.faceName.rename.empty"));
            return;
        }
        if (newName.equals(activeName.name())) {
            statusLabel.setText(I18n.get("ui.faceName.rename.unchanged"));
            return;
        }

        statusLabel.setText(I18n.format("ui.faceName.renaming", newName));
        setTask(new Task<NameRecord>() {
            @Override
            protected NameRecord call() throws SQLException {
                return faceToNameService.renameName(activeName.id(), newName);
            }
        });

        activeTask.setOnSucceeded(e -> {
            activeName = (NameRecord) activeTask.getValue();
            statusLabel.setText(I18n.format("ui.faceName.renamed", activeName.name()));
            loadNames(true); // refresh list and candidates for the renamed name
        });

        activeTask.setOnFailed(e -> {
            Throwable error = activeTask.getException();
            statusLabel.setText(error instanceof IllegalArgumentException
                    ? error.getMessage()
                    : I18n.get("ui.faceName.renameFailed"));
            if (!(error instanceof IllegalArgumentException)) {
                handleFailure(I18n.get("ui.faceName.renameFailed"), error);
            }
        });

        startTask("facename-renamer");
    }

    /**
     * Lets the user pick an output folder and exports every image that
     * contains the selected person into it, copying thumbnails in place of
     * originals that are no longer available. The export runs on a background
     * task so the UI stays responsive.
     */
    private void onExport() {
        if (activeName == null) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("ui.faceName.exportFolderChooser"));
        Window owner = getScene() != null ? getScene().getWindow() : null;
        java.io.File selected = chooser.showDialog(owner);
        if (selected == null) {
            return;
        }
        Path outputDir = selected.toPath();
        NameRecord name = activeName;

        statusLabel.setText(I18n.format("ui.faceName.exporting", name.name()));
        setTask(new Task<FaceToNameService.ExportResult>() {
            @Override
            protected FaceToNameService.ExportResult call() throws Exception {
                return faceToNameService.exportImagesForName(name.id(), outputDir);
            }
        });

        activeTask.setOnSucceeded(e -> {
            FaceToNameService.ExportResult result =
                    (FaceToNameService.ExportResult) activeTask.getValue();
            if (result.images() == 0) {
                statusLabel.setText(I18n.format("ui.faceName.exportNone", name.name()));
            } else {
                statusLabel.setText(
                        I18n.format("ui.faceName.exported",
                                result.images(), name.name(), outputDir)
                        + " " + I18n.format("ui.faceName.exportBreakdown",
                                result.originalsCopied(),
                                result.thumbnailsCopied(),
                                result.missing()));
            }
        });

        activeTask.setOnFailed(e ->
                handleFailure(I18n.get("ui.faceName.exportFailed"), activeTask.getException()));

        startTask("facename-exporter");
    }

    /**
     * Builds a wrapped tooltip for a control explanation.
     *
     * @param text the tooltip text
     * @return a tooltip that wraps its text within a maximum width
     */
    private static Tooltip tooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(420);
        return tooltip;
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
        alert.setTitle(I18n.get("ui.faceName.alertTitle"));
        alert.setHeaderText(message);
        alert.setContentText(error.getMessage() == null ? error.toString() : error.getMessage());
        Window window = getScene() != null ? getScene().getWindow() : null;
        if (window != null) {
            alert.initOwner(window);
        }
        alert.showAndWait();
    }

    /** Result bundle for a selected name: its best tagged faces and the
     *  best unnamed candidates. */
    private record NameContent(List<SimilarityResult> namedFaces,
                               List<SimilarityResult> candidates) {
    }
}