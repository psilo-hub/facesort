package free.svoss.facesort.ui;

import free.svoss.facesort.i18n.I18n;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.service.NamingService;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
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
    private final Button tagSelectedButton = new Button(I18n.get("ui.randomName.tagSelected"));
    private final Button nextButton = new Button(I18n.get("ui.randomName.next"));
    private final FlowPane facesPane = new FlowPane(10, 10);

    private final TaskRunner taskRunner = new TaskRunner();
    private final FaceUi.FaceActions faceActions;

    /**
     * Creates the random-tagging tab.
     *
     * @param namingService the naming service; must not be null
     */
    public RandomNameView(NamingService namingService) {
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
        loadSample();
    }

    /**
     * Builds the name input row, the scrolling face grid and the status bar.
     */
    private void buildUi() {
        nameField.setPromptText(I18n.get("ui.randomName.namePrompt"));
        tagSelectedButton.getStyleClass().add("primary");
        tagSelectedButton.setDisable(true);
        tagSelectedButton.setOnAction(e -> onTagSelected());
        nextButton.setOnAction(e -> loadSample());

        pathFilterField.setPromptText(I18n.get("ui.randomName.pathFilterPrompt"));
        pathFilterField.setTooltip(new Tooltip(I18n.get("ui.randomName.pathFilterTooltip")));
        pathFilterField.setOnAction(e -> loadSample());
        HBox.setHgrow(pathFilterField, Priority.ALWAYS);
        HBox.setHgrow(nameField, Priority.ALWAYS);

        nameExistsLabel.setWrapText(true);
        nameField.textProperty().addListener((obs, oldText, newText) -> checkNameExists());
        HBox controls = new HBox(8,
                new Label(I18n.get("ui.randomName.name")), nameField, nameExistsLabel, tagSelectedButton, nextButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(10));

        HBox filterBar = new HBox(8, new Label(I18n.get("ui.randomName.pathFilter")), pathFilterField);
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
        statusLabel.setText(I18n.get("ui.randomName.loading"));
        facesPane.getChildren().clear();

        Task<List<FaceRecord>> task = new Task<>() {
            @Override
            protected List<FaceRecord> call() throws SQLException {
                return namingService.findRandomUnnamed(BATCH_SIZE, pathPrefix);
            }
        };

        task.setOnSucceeded(e -> {
            List<FaceRecord> faces = task.getValue();
            showFaces(faces);
            setBusy(false);
            statusLabel.setText(faces.isEmpty()
                    ? (pathPrefix.isEmpty()
                            ? I18n.get("ui.randomName.noneLeft")
                            : I18n.get("ui.randomName.noneMatch"))
                    : I18n.format("ui.randomName.sampleInfo", faces.size()));
        });

        task.setOnFailed(e -> {
            setBusy(false);
            handleFailure(I18n.get("ui.randomName.loadFailed"), task.getException());
        });

        taskRunner.start(task, "random-sample-loader");
    }

    /**
     * Checks the name typed into the field against existing names and shows
     * the outcome in the adjacent label. Runs on a background task and ignores
     * stale results once the field changes again.
     */
    private void checkNameExists() {
        FaceUi.checkNameExistence(nameField, nameExistsLabel, taskRunner,
                "randomname-name-exists",
                "ui.randomName.nameExists", "ui.randomName.newName",
                namingService::findName);
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
            VBox card = FaceUi.faceCard(face, THUMBNAIL_SIZE);

            FaceUi.installPathTooltip(card, face, "randomname-path-tooltip-",
                    namingService::findImagePaths);

            Label idLabel = new Label(I18n.format("ui.randomName.idPrefix", face.id()));
            idLabel.getStyleClass().add("secondary-text");

            card.getChildren().add(idLabel);
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
     * Installs a right-click context menu on a face card: the standard "Open
     * Original" and "Open containing folder" entries plus the "Paste path to
     * path filter" entry that drops the source folder into the filter field,
     * grayed out while the media file has no stored path.
     *
     * @param card the card to attach the menu to
     * @param face the face whose source image should be openable
     */
    private void installContextMenu(VBox card, FaceRecord face) {
        MenuItem pasteFilterItem = new MenuItem(I18n.get("common.pastePathToFilter"));
        Optional<Path> filterFolder = filterFolderFor(face);
        pasteFilterItem.setDisable(filterFolder.isEmpty());
        pasteFilterItem.setOnAction(ev -> filterFolder.ifPresent(this::applyFilterFolder));
        faceActions.installFaceMenu(card, face, pasteFilterItem);
    }

    /**
     * Resolves the folder to paste into the path filter for a face, or empty
     * when the media file has no stored path.
     *
     * @param face the face whose media file folder to resolve
     * @return the folder to filter by, or empty when unavailable
     */
    private Optional<Path> filterFolderFor(FaceRecord face) {
        try {
            return namingService.resolveFilterFolder(face.imageHash());
        } catch (SQLException ex) {
            statusLabel.setText(I18n.get("common.pathUnavailable"));
            return Optional.empty();
        }
    }

    /**
     * Drops a folder into the path filter field and reloads the sample.
     *
     * @param folder the folder to filter by
     */
    private void applyFilterFolder(Path folder) {
        pathFilterField.setText(folder.toString());
        pathFilterField.positionCaret(pathFilterField.getLength());
        loadSample();
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
            statusLabel.setText(I18n.get("ui.randomName.typeNameFirst"));
            return;
        }
        List<Long> faceIds = selectedFaceIds();
        if (faceIds.isEmpty()) {
            statusLabel.setText(I18n.get("ui.randomName.clickToSelect"));
            return;
        }

        setBusy(true);
        statusLabel.setText(I18n.format("ui.randomName.tagging", faceIds.size(), name));

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws SQLException {
                long nameId = namingService.createOrFindName(name);
                for (Long faceId : faceIds) {
                    namingService.tagFace(faceId, nameId);
                }
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            facesPane.getChildren().removeIf(node ->
                    node.getUserData() instanceof Long id && faceIds.contains(id));
            setBusy(false);
            statusLabel.setText(I18n.format("ui.randomName.tagged", faceIds.size(), name));
        });

        task.setOnFailed(e -> {
            setBusy(false);
            handleFailure(I18n.get("ui.randomName.tagFailed"), task.getException());
        });

        taskRunner.start(task, "random-tagger");
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
     * Raises a modal error dialog and resets the status bar.
     *
     * @param message the header text
     * @param error   the underlying exception
     */
    private void handleFailure(String message, Throwable error) {
        statusLabel.setText(message + ".");
        Window window = getScene() != null ? getScene().getWindow() : null;
        FaceUi.showError(window, "ui.randomName.alertTitle", message, error);
    }
}