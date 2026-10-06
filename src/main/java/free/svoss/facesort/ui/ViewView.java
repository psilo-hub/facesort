package free.svoss.facesort.ui;

import free.svoss.facesort.i18n.I18n;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.service.ViewService;
import javafx.animation.PauseTransition;
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
import javafx.util.Duration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * "View" tab: shows a grid of name cards (representative face, name, face
 * count). Clicking a card shows the images that contain faces with that name;
 * clicking a thumbnail opens the original file in the system default viewer.
 *
 * <p>The view owns no business logic; all queries are delegated to
 * {@link ViewService}. Queries run on background {@link Task}s so the UI
 * stays responsive, and database errors are surfaced in an error dialog.
 * Typing in the filter field does not rebuild the grid per keystroke: the
 * filtering is debounced, and the decoded thumbnails are kept in a bounded
 * cache so a rebuild re-uses them instead of decoding the JPEGs again.</p>
 */
public class ViewView extends BorderPane implements Refreshable {

    private static final double NAME_THUMBNAIL_SIZE = 120.0;
    private static final double IMAGE_THUMBNAIL_SIZE = 150.0;

    /** Quiet period after the last keystroke before the name grid is filtered. */
    private static final long FILTER_DEBOUNCE_MS = 300;

    /** Thumbnails kept across grid rebuilds, keyed by image hash or face id. */
    private static final int THUMBNAIL_CACHE_CAPACITY = 256;

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
    private final LruCache<String, Image> thumbnails = new LruCache<>(THUMBNAIL_CACHE_CAPACITY);
    private final Debouncer filterDebounce =
            new Debouncer(FILTER_DEBOUNCE_MS, new PauseTransitionScheduler(), this::applyFilter);
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
        filterField.textProperty().addListener((obs, oldValue, newValue) -> filterDebounce.trigger());
        HBox.setHgrow(filterField, Priority.NEVER);

        HBox topBar = new HBox(10, backButton, titleLabel, filterField);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(10));
        titleLabel.getStyleClass().add("subtitle-label");

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
     *
     * <p>Any keystroke-triggered run still pending is dropped: the grid is
     * built from the current text, so a stale trigger must not rebuild it a
     * second time.</p>
     */
    private void applyFilter() {
        filterDebounce.cancel();
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

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("name-label");

        int count = summary.faceCount();
        Label countLabel = new Label(I18n.format(count == 1 ? "ui.view.face" : "ui.view.faces", count));
        countLabel.getStyleClass().add("count-label");

        card.getChildren().addAll(
                FaceUi.thumb(representativeThumbnail(summary.representative()), NAME_THUMBNAIL_SIZE),
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
        box.getStyleClass().add("image-thumbnail");

        ImageView view = FaceUi.thumb(cachedThumbnail("image:" + image.hash(), image.thumbnailJpg()),
                IMAGE_THUMBNAIL_SIZE);

        String hash = image.hash();
        Label caption = new Label(view.getImage() != null
                ? hash.substring(0, Math.min(8, hash.length()))
                : I18n.get("ui.view.noPreview"));
        caption.getStyleClass().add("caption-label");

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
     * Returns the decoded thumbnail of a representative face, or {@code null}
     * when there is no face or no sub-image to show.
     *
     * @param face the representative face, possibly null
     * @return the decoded thumbnail, or {@code null}
     */
    private Image representativeThumbnail(FaceRecord face) {
        if (face == null) {
            return null;
        }
        return cachedThumbnail("face:" + face.id(), face.subImageJpg());
    }

    /**
     * Returns the decoded thumbnail stored under the given key, decoding
     * {@code jpg} on the first read and re-using the cached image afterwards.
     * The keys are content identities (an image hash, a face id), so a cached
     * entry cannot go stale.
     *
     * @param key stable identity of the picture
     * @param jpg the encoded thumbnail, possibly null or empty
     * @return the decoded image, or {@code null} when there is nothing to show
     */
    private Image cachedThumbnail(String key, byte[] jpg) {
        if (jpg == null || jpg.length == 0) {
            return null;
        }
        return thumbnails.get(key, () -> new Image(new ByteArrayInputStream(jpg)));
    }

    /**
     * Untags every face in the given image that is currently tagged with the
     * name selected at this moment, then reloads that name's image grid so the
     * image disappears from the list. Selecting a different person while the
     * untag runs does not redirect it.
     *
     * @param hash content hash of the image
     */
    private void untagFaces(String hash) {
        NameBoundWork.Bound<Integer> untag = NameBoundWork.forSelectedName(this::activeNameRecord,
                name -> viewService.untagFacesFromImage(hash, name.id()));
        statusLabel.setText(I18n.format("ui.view.untagging", untag.name().name()));
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return untag.work().call();
            }
        };

        task.setOnSucceeded(e -> {
            int count = task.getValue();
            String name = untag.name().name();
            statusLabel.setText(I18n.format("ui.view.untagged", count, name));
            openNameImages(untag.name().id(), name);
        });

        task.setOnFailed(e -> {
            statusLabel.setText(I18n.get("ui.view.untagFailed"));
            handleFailure(I18n.get("ui.view.untagFailedHeader"), task.getException());
        });

        taskRunner.start(task, "view-untagger");
    }

    /**
     * The name whose images are currently on screen, as a record so a
     * background action can capture it.
     *
     * @return the active name
     */
    private NameRecord activeNameRecord() {
        return new NameRecord(activeNameId, Objects.requireNonNullElse(activeNameText, ""), null);
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

    /**
     * The FX backend of {@link #filterDebounce}: one pause transition that is
     * re-armed on every keystroke and runs the deferred action once the field
     * has been quiet for the debounce delay.
     */
    private final class PauseTransitionScheduler implements Debouncer.Scheduler {

        private final PauseTransition transition = new PauseTransition();

        @Override
        public void schedule(Runnable action, long delayMs) {
            transition.setDuration(Duration.millis(delayMs));
            transition.setOnFinished(event -> action.run());
            transition.playFromStart();
        }

        @Override
        public void cancel() {
            transition.stop();
        }
    }
}