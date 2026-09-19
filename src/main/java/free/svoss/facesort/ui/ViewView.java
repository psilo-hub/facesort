package free.svoss.facesort.ui;

import free.svoss.facesort.i18n.I18n;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.service.ViewService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(ViewView.class.getName());
    private static final double NAME_THUMBNAIL_SIZE = 120.0;
    private static final double IMAGE_THUMBNAIL_SIZE = 150.0;

    private final ViewService viewService;

    private final Button backButton = new Button(I18n.get("ui.view.backToNames"));
    private final Label titleLabel = new Label(I18n.get("ui.view.names"));
    private final Label statusLabel = new Label("");
    private final ScrollPane scrollPane = new ScrollPane();
    private final FlowPane namesPane = new FlowPane(12, 12);
    private final FlowPane imagesPane = new FlowPane(12, 12);

    private Task<?> activeTask;
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
        buildUi();
        loadNames();
    }

    /**
     * Builds the toolbar, the scrolling content area and the status bar.
     */
    private void buildUi() {
        backButton.setOnAction(e -> loadNames());
        backButton.setVisible(false);

        HBox topBar = new HBox(10, backButton, titleLabel);
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
        setBusy(true);
        statusLabel.setText(I18n.get("ui.view.loadingNames"));

        setTask(new Task<>() {
            @Override
            protected List<ViewService.NameSummary> call() throws SQLException {
                return viewService.getNameSummaries();
            }
        });

        activeTask.setOnSucceeded(e -> {
            @SuppressWarnings("unchecked")
            List<ViewService.NameSummary> summaries = (List<ViewService.NameSummary>) activeTask.getValue();
            namesPane.getChildren().clear();
            for (ViewService.NameSummary summary : summaries) {
                namesPane.getChildren().add(buildNameCard(summary));
            }
            scrollPane.setContent(namesPane);
            titleLabel.setText(I18n.format("ui.view.namesCount", summaries.size()));
            backButton.setVisible(false);
            setBusy(false);
            statusLabel.setText(
                    summaries.isEmpty() ? I18n.get("ui.view.noNames") : "");
        });

        activeTask.setOnFailed(e -> {
            setBusy(false);
            handleFailure(I18n.get("ui.view.loadNamesFailed"), activeTask.getException());
        });

        startTask("view-names-loader");
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
        setBusy(true);
        statusLabel.setText(I18n.format("ui.view.loadingImages", displayName));

        setTask(new Task<>() {
            @Override
            protected List<ViewService.NamedImage> call() throws SQLException {
                return viewService.getImagesForName(nameId);
            }
        });

        activeTask.setOnSucceeded(e -> {
            @SuppressWarnings("unchecked")
            List<ViewService.NamedImage> images = (List<ViewService.NamedImage>) activeTask.getValue();
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

        activeTask.setOnFailed(e -> {
            setBusy(false);
            handleFailure(I18n.get("ui.view.loadImagesFailed"), activeTask.getException());
        });

        startTask("view-images-loader");
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
                thumbnail(summary.representative(), NAME_THUMBNAIL_SIZE),
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
                openOriginal(hash);
            }
        });
        installContextMenu(box, image);
        return box;
    }

    /**
     * Installs a right-click context menu on an image thumbnail with "Open
     * Original" and "Untag from ..." entries.
     *
     * @param box   the thumbnail box to attach the menu to
     * @param image the named image the box displays
     */
    private void installContextMenu(VBox box, ViewService.NamedImage image) {
        String hash = image.hash();
        box.setOnContextMenuRequested(e -> {
            MenuItem openOriginalItem = new MenuItem(I18n.get("common.openOriginal"));
            openOriginalItem.setDisable(!isOriginalAvailable(hash));
            openOriginalItem.setOnAction(ev -> openOriginal(hash));
            MenuItem untagItem = new MenuItem(I18n.format("ui.view.untagFrom", activeNameText));
            untagItem.setOnAction(ev -> untagFaces(hash));
            new ContextMenu(openOriginalItem, untagItem)
                    .show(box, e.getScreenX(), e.getScreenY());
        });
    }

    /**
     * Tells whether an original file for the given image exists on disk.
     *
     * @param hash content hash of the image
     * @return {@code true} if the original file is available
     */
    private boolean isOriginalAvailable(String hash) {
        try {
            return viewService.isOriginalAvailable(hash);
        } catch (SQLException ex) {
            LOG.log(Level.WARNING, "Could not check original availability for " + hash, ex);
            return false;
        }
    }

    /**
     * Opens the original file of an image in the default viewer.
     *
     * @param hash content hash of the image
     */
    private void openOriginal(String hash) {
        try {
            boolean opened = viewService.openOriginal(hash);
            statusLabel.setText(opened ? "" : I18n.get("common.originalNotFound"));
        } catch (IOException | SQLException ex) {
            LOG.log(Level.WARNING, "Could not open original image " + hash, ex);
            handleFailure(I18n.get("common.openOriginalFailed"), ex);
        }
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
        setTask(new Task<Integer>() {
            @Override
            protected Integer call() throws SQLException {
                return viewService.untagFacesFromImage(hash, activeNameId);
            }
        });

        activeTask.setOnSucceeded(e -> {
            int count = (Integer) activeTask.getValue();
            statusLabel.setText(I18n.format("ui.view.untagged", count, activeNameText));
            openNameImages(activeNameId, activeNameText);
        });

        activeTask.setOnFailed(e -> {
            statusLabel.setText(I18n.get("ui.view.untagFailed"));
            handleFailure(I18n.get("ui.view.untagFailedHeader"), activeTask.getException());
        });

        startTask("view-untagger");
    }

    /**
     * Decodes a face's JPEG sub-image, or returns an empty ImageView when the
     * sub-image is unavailable.
     *
     * @param face the face record to render
     * @param size the display size for the thumbnail
     * @return the thumbnail node
     */
    private static ImageView thumbnail(FaceRecord face, double size) {
        ImageView view = new ImageView();
        byte[] jpg = face != null ? face.subImageJpg() : null;
        if (jpg != null && jpg.length > 0) {
            view.setImage(new Image(new ByteArrayInputStream(jpg)));
        }
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
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
     * @param name the thread name
     */
    private void startTask(String name) {
        if (activeTask == null) {
            return;
        }
        Thread thread = new Thread(activeTask, name);
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
        alert.setTitle(I18n.get("ui.view.alertTitle"));
        alert.setHeaderText(message);
        alert.setContentText(error.getMessage() == null ? error.toString() : error.getMessage());
        Window window = getScene() != null ? getScene().getWindow() : null;
        if (window != null) {
            alert.initOwner(window);
        }
        alert.showAndWait();
    }
}