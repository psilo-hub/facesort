package free.svoss.facesort.ui;

import free.svoss.facesort.i18n.I18n;
import free.svoss.facesort.service.ViewService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
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
import java.util.Locale;

/**
 * "View" tab: shows a grid of name cards (representative face, name, face
 * count). Clicking a card shows the images that contain faces with that name;
 * clicking a thumbnail opens the original file in the system default viewer.
 *
 * <p>The view owns no business logic; all queries are delegated to
 * {@link ViewService}. Queries run on background {@link Task}s so the UI
 * stays responsive, and database errors are surfaced in an error dialog.</p>
 */
public class ViewView extends BorderPane implements Refreshable {

    private static final double NAME_THUMBNAIL_SIZE = 120.0;
    private static final double IMAGE_THUMBNAIL_SIZE = 150.0;

    private final ViewService viewService;

    private final Button backButton = new Button(I18n.get("ui.view.backToNames"));
    private final Label titleLabel = new Label(I18n.get("ui.view.names"));
    private final TextField filterField = new TextField();
    private final Label statusLabel = new Label("");
    private final ScrollPane scrollPane = new ScrollPane();
    private final FlowPane namesPane = new FlowPane(12, 12);
    private final FlowPane imagesPane = new FlowPane(12, 12);

    private List<ViewService.NameSummary> summaries = List.of();

    private final TaskRunner taskRunner = new TaskRunner();
    private final FaceUi.FaceActions faceActions;
    private boolean imagesMode;
    private long activeNameId;
    private String activeNameText = "";

    /**
     * Creates the view tab.
     *
     * @param viewService the view service; must not be null
     */
    public ViewView(ViewService viewService) {
        this.viewService = viewService;
        this.faceActions = new FaceUi.FaceActions(
                new FaceUi.FaceActions.Source() {
                    @Override
                    public boolean isOriginalAvailable(String hash) throws SQLException {
                        return viewService.isOriginalAvailable(hash);
                    }

                    @Override
                    public boolean isContainingFolderAvailable(String hash) throws SQLException {
                        return viewService.isContainingFolderAvailable(hash);
                    }

                    @Override
                    public boolean openOriginal(String hash) throws IOException, SQLException {
                        return viewService.openOriginal(hash);
                    }

                    @Override
                    public boolean openContainingFolder(String hash) throws IOException, SQLException {
                        return viewService.openContainingFolder(hash);
                    }
                },
                statusLabel::setText,
                (hash, ex) -> statusLabel.setText(I18n.get("common.originalCheckFailed")),
                (message, ex) -> {
                    statusLabel.setText(message);
                    handleFailure(message, ex);
                });
        buildUi();
        loadNames();
    }

    /**
     * Builds the toolbar, the scrolling content area and the status bar.
     */
    private void buildUi() {
        backButton.setOnAction(e -> loadNames());
        backButton.setVisible(false);

        filterField.setPromptText(I18n.get("ui.view.filterNames"));
        filterField.setPrefWidth(200);
        filterField.setMaxWidth(200);
        filterField.textProperty().addListener((obs, oldValue, newValue) -> applyFilter());
        HBox.setHgrow(filterField, Priority.NEVER);

        HBox topBar = new HBox(10, backButton, titleLabel, filterField);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(10));
        titleLabel.setStyle("-fx-font-size: 14; -fx-font-weight: bold;");

        namesPane.setPadding(new Insets(10));
        imagesPane.setPadding(new Insets(10));

        scrollPane.setFitToWidth(true);
        scrollPane.setContent(namesPane);

        statusLabel.setWrapText(true);
        BorderPane.setMargin(statusLabel, new Insets(0, 10, 10, 10));

        setTop(topBar);
        setCenter(scrollPane);
        setBottom(statusLabel);
    }

    /**
     * Loads the name summaries and shows them as a grid of cards.
     */
    private void loadNames() {
        imagesMode = false;
        activeNameId = 0;
        activeNameText = "";
        filterField.setVisible(true);
        setBusy(true);
        statusLabel.setText(I18n.get("ui.view.loadingNames"));

        Task<List<ViewService.NameSummary>> task = new Task<>() {
            @Override
            protected List<ViewService.NameSummary> call() throws SQLException {
                return viewService.getNameSummaries();
            }
        };

        task.setOnSucceeded(e -> {
            summaries = task.getValue();
            applyFilter();
            scrollPane.setContent(namesPane);
            backButton.setVisible(false);
            setBusy(false);
        });

        task.setOnFailed(e -> {
            setBusy(false);
            handleFailure(I18n.get("ui.view.loadNamesFailed"), task.getException());
        });

        taskRunner.start(task, "view-names-loader");
    }

    /**
     * Re-renders the name grid according to the current filter text: cards are
     * kept only for names whose (case-insensitive) name contains the trimmed
     * filter, and the title shows the matching count when a filter is active.
     * Does nothing while the images grid of a name is shown.
     */
    private void applyFilter() {
        if (imagesMode) {
            return;
        }
        List<ViewService.NameSummary> filtered = filterNames(summaries, filterField.getText());
        namesPane.getChildren().clear();
        for (ViewService.NameSummary summary : filtered) {
            namesPane.getChildren().add(buildNameCard(summary));
        }
        if (filterField.getText().isBlank()) {
            titleLabel.setText(I18n.format("ui.view.namesCount", filtered.size()));
            statusLabel.setText(summaries.isEmpty() ? I18n.get("ui.view.noNames") : "");
        } else {
            titleLabel.setText(I18n.format("ui.view.namesCountOf", filtered.size(), summaries.size()));
            statusLabel.setText(filtered.isEmpty()
                    ? I18n.format("ui.view.noMatchingNames", filterField.getText().trim())
                    : "");
        }
    }

    /**
     * Returns the summaries whose name contains the given filter. Matching is
     * case-insensitive, the filter is trimmed before comparison, and an empty
     * (or null) filter returns the input list unchanged.
     *
     * @param summaries summaries to filter; must not be null
     * @param filter    filter text, possibly null or blank
     * @return the matching summaries in input order
     */
    static List<ViewService.NameSummary> filterNames(
            List<ViewService.NameSummary> summaries, String filter) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return summaries;
        }
        List<ViewService.NameSummary> result = new ArrayList<>();
        for (ViewService.NameSummary summary : summaries) {
            if (summary.name().name().toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(summary);
            }
        }
        return result;
    }

    /**
     * Reloads the current view: the name grid when browsing names, or the
     * image grid of the still-active name. Called by the tab window when this
     * tab is selected, so counts reflect other tabs' changes.
     */
    @Override
    public void refresh() {
        if (imagesMode && activeNameText != null && !activeNameText.isBlank()) {
            openNameImages(activeNameId, activeNameText);
        } else {
            loadNames();
        }
    }

    /**
     * Loads the images for the given name and shows them as a thumbnail grid.
     *
     * @param nameId      id of the name
     * @param displayName the name to show in the title
     */
    private void openNameImages(long nameId, String displayName) {
        imagesMode = true;
        activeNameId = nameId;
        activeNameText = displayName;
        filterField.setVisible(false);
        setBusy(true);
        statusLabel.setText(I18n.format("ui.view.loadingImages", displayName));

        Task<List<ViewService.NamedImage>> task = new Task<>() {
            @Override
            protected List<ViewService.NamedImage> call() throws SQLException {
                return viewService.getImagesForName(nameId);
            }
        };

        task.setOnSucceeded(e -> {
            List<ViewService.NamedImage> images = task.getValue();
            imagesPane.getChildren().clear();
            for (ViewService.NamedImage image : images) {
                imagesPane.getChildren().add(buildImageThumbnail(image));
            }
            scrollPane.setContent(imagesPane);
            titleLabel.setText(I18n.format("ui.view.imagesCount", displayName, images.size()));
            backButton.setVisible(true);
            setBusy(false);
            statusLabel.setText(images.isEmpty() ? I18n.get("ui.view.noImages") : "");
        });

        task.setOnFailed(e -> {
            setBusy(false);
            handleFailure(I18n.get("ui.view.loadImagesFailed"), task.getException());
        });

        taskRunner.start(task, "view-images-loader");
    }

    /**
     * Builds a clickable name card: representative face, name and face count.
     *
     * @param summary the name summary to render
     * @return the card node
     */
    private VBox buildNameCard(ViewService.NameSummary summary) {
        String name = summary.name().name();

        VBox card = new VBox(6);
        card.getStyleClass().add("name-card");
        card.setAlignment(Pos.TOP_CENTER);
        card.setPadding(new Insets(8));
        card.setPrefWidth(NAME_THUMBNAIL_SIZE + 16);
        card.setStyle("-fx-background-color: #f4f4f8; -fx-background-radius: 6;"
                + " -fx-border-color: #ccccdd; -fx-border-radius: 6; -fx-cursor: hand;");

        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13;");

        int count = summary.faceCount();
        Label countLabel = new Label(I18n.format(count == 1 ? "ui.view.face" : "ui.view.faces", count));
        countLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #666666;");

        card.getChildren().addAll(
                FaceUi.thumb(summary.representative(), NAME_THUMBNAIL_SIZE),
                nameLabel, countLabel);
        card.setOnMouseClicked(e -> openNameImages(summary.name().id(), name));
        return card;
    }

    /**
     * Builds a clickable image thumbnail for the images grid.
     *
     * @param image the named image to render
     * @return the thumbnail node
     */
    private VBox buildImageThumbnail(ViewService.NamedImage image) {
        VBox box = new VBox(4);
        box.setAlignment(Pos.TOP_CENTER);
        box.setPadding(new Insets(6));
        box.setPrefWidth(IMAGE_THUMBNAIL_SIZE + 16);
        box.setStyle("-fx-background-color: #f4f4f8; -fx-background-radius: 6;"
                + " -fx-border-color: #ccccdd; -fx-border-radius: 6; -fx-cursor: hand;");

        ImageView view = new ImageView();
        byte[] jpg = image.thumbnailJpg();
        if (jpg != null && jpg.length > 0) {
            view.setImage(new Image(new ByteArrayInputStream(jpg)));
        }
        view.setFitWidth(IMAGE_THUMBNAIL_SIZE);
        view.setFitHeight(IMAGE_THUMBNAIL_SIZE);
        view.setPreserveRatio(true);
        view.setSmooth(true);

        String hash = image.hash();
        Label caption = new Label(jpg != null ? hash.substring(0, Math.min(8, hash.length())) : I18n.get("ui.view.noPreview"));
        caption.setStyle("-fx-font-size: 10; -fx-text-fill: #666666;");

        box.getChildren().addAll(view, caption);
        box.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                faceActions.openOriginal(hash);
            }
        });
        installContextMenu(box, image);
        return box;
    }

    /**
     * Installs a right-click context menu on an image thumbnail with "Open
     * Original", "Open containing folder" and "Untag from ..." entries.
     *
     * @param box   the thumbnail box to attach the menu to
     * @param image the named image the box displays
     */
    private void installContextMenu(VBox box, ViewService.NamedImage image) {
        MenuItem untagItem = new MenuItem(I18n.format("ui.view.untagFrom", activeNameText));
        untagItem.setOnAction(ev -> untagFaces(image.hash()));
        faceActions.installMenu(box, image.hash(), untagItem);
    }

    /**
     * Untags every face in the given image that is currently tagged with the
     * active name, then reloads the image grid so the image disappears from
     * the list.
     *
     * @param hash content hash of the image
     */
    private void untagFaces(String hash) {
        statusLabel.setText(I18n.format("ui.view.untagging", activeNameText));
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws SQLException {
                return viewService.untagFacesFromImage(hash, activeNameId);
            }
        };

        task.setOnSucceeded(e -> {
            int count = task.getValue();
            statusLabel.setText(I18n.format("ui.view.untagged", count, activeNameText));
            openNameImages(activeNameId, activeNameText);
        });

        task.setOnFailed(e -> {
            statusLabel.setText(I18n.get("ui.view.untagFailed"));
            handleFailure(I18n.get("ui.view.untagFailedHeader"), task.getException());
        });

        taskRunner.start(task, "view-untagger");
    }

    /**
     * Enables or disables interaction while a query runs.
     *
     * @param busy {@code true} while a query runs
     */
    private void setBusy(boolean busy) {
        backButton.setDisable(busy);
        scrollPane.setDisable(busy);
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
        FaceUi.showError(window, "ui.view.alertTitle", message, error);
    }
}