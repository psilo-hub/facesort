package free.svoss.facesort.ui;

import free.svoss.facesort.i18n.I18n;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Shared face-grid plumbing used by every face-related view: decoding a face's
 * JPEG sub-image into a thumbnail, building the candidate cards, installing the
 * "Open Original"/"Open containing folder" context menu, the path tooltip, the
 * name-existence check and the modal error dialog.
 */
final class FaceUi {

    /** Status styles for the name-existence check (see {@code css/styles.css}). */
    static final String NAME_STATUS_EXISTS = "name-status-exists";
    static final String NAME_STATUS_NEW = "name-status-new";

    private FaceUi() {
        // Utility class - not instantiable
    }

    /**
     * Attaches the application-wide stylesheet to a dialog pane so CSS classes
     * apply inside dialogs (which have their own scenes).
     *
     * @param dialogPane the dialog pane to style
     */
    static void addApplicationStylesheet(DialogPane dialogPane) {
        var cssResource = FaceUi.class.getResource("/css/styles.css");
        if (cssResource != null) {
            dialogPane.getStylesheets().add(cssResource.toExternalForm());
        }
    }

    /**
     * Sets the name-existence status style on a label: removes any previous
     * status class and adds the given one, or only clears when {@code null}.
     *
     * @param label       the label to restyle
     * @param statusClass the {@code name-status-*} class to add, or {@code null}
     */
    static void setStatusClass(Label label, String statusClass) {
        label.getStyleClass().removeAll(NAME_STATUS_EXISTS, NAME_STATUS_NEW);
        if (statusClass != null) {
            label.getStyleClass().add(statusClass);
        }
    }

    /**
     * A database lookup of a single value by an image hash that may fail with
     * {@link SQLException}.
     */
    @FunctionalInterface
    interface Lookup<T> {
        T byHash(String hash) throws SQLException;
    }

    /**
     * Shows the face's JPEG thumbnail in the given view, or clears it when the
     * sub-image is unavailable.
     *
     * @param view the image view to update
     * @param face the face whose bytes should be shown
     */
    static void setImage(ImageView view, FaceRecord face) {
        view.setImage(toImage(face));
    }

    /**
     * Decodes a face's JPEG sub-image, or returns {@code null} when no
     * sub-image is available.
     *
     * @param face the face record to render
     * @return the decoded image, or {@code null} when unavailable
     */
    static Image toImage(FaceRecord face) {
        byte[] jpg = face != null ? face.subImageJpg() : null;
        if (jpg == null || jpg.length == 0) {
            return null;
        }
        return new Image(new ByteArrayInputStream(jpg));
    }

    /**
     * Builds a face thumbnail of the given display size.
     *
     * @param face the face whose sub-image should be shown
     * @param size the display size (width and height)
     * @return the configured image view
     */
    static ImageView thumb(FaceRecord face, double size) {
        ImageView view = new ImageView();
        setImage(view, face);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }

    /**
     * Builds a candidate card for the given face: a styled {@code VBox}
     * carrying the face id in its user data and prefilled with the face
     * thumbnail. Callers append the caption and any action controls.
     *
     * @param face      the face to render
     * @param thumbSize the thumbnail display size
     * @return the card node
     */
    static VBox faceCard(FaceRecord face, double thumbSize) {
        VBox card = new VBox(4);
        card.setAlignment(Pos.TOP_CENTER);
        card.setPadding(new Insets(4));
        card.setUserData(face.id());
        card.getStyleClass().add("candidate-card");
        card.getChildren().add(thumb(face, thumbSize));
        return card;
    }

    /**
     * Builds a tooltip that wraps its text within a maximum width.
     *
     * @param text the tooltip text
     * @return a tooltip that wraps its text
     */
    static Tooltip wrappedTooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(420);
        return tooltip;
    }

    /**
     * Installs a hover tooltip showing the on-disk paths of the face's source
     * image. The paths are looked up asynchronously on a daemon thread so the
     * UI stays responsive; the tooltip shows progress and fallback states. The
     * thread is not routed through the view's {@link TaskRunner}, so the
     * tooltip check never cancels the view's active task.
     *
     * @param node       the node to attach the tooltip to
     * @param face       the face whose source image paths should be shown
     * @param threadName base name of the daemon thread (the face id is appended)
     * @param lookup     resolves the stored paths for an image hash
     */
    static void installPathTooltip(Node node, FaceRecord face, String threadName,
                                   Lookup<List<String>> lookup) {
        Tooltip tooltip = new Tooltip(I18n.get("common.loadingPath"));
        Tooltip.install(node, tooltip);
        Task<List<String>> paths = new Task<>() {
            @Override
            protected List<String> call() throws SQLException {
                return lookup.byHash(face.imageHash());
            }
        };
        paths.setOnSucceeded(e -> {
            List<String> result = paths.getValue();
            tooltip.setText(result.isEmpty()
                    ? I18n.get("common.noStoredPath")
                    : String.join(System.lineSeparator(), result));
        });
        paths.setOnFailed(e -> tooltip.setText(I18n.get("common.pathUnavailable")));
        Thread thread = new Thread(paths, threadName + face.id());
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Checks the name typed into a field against existing names and shows the
     * outcome in the adjacent label. Runs on a background task and ignores
     * stale results once the field changes again.
     *
     * @param field     the text field being typed into
     * @param label     the label showing the existence outcome
     * @param runner    the view's task runner
     * @param threadName name of the daemon thread running the task
     * @param existsKey i18n key for "name already exists with N faces"
     * @param newKey    i18n key for "new name"
     * @param findName  resolves the typed name to a {@link NameRecord}
     */
    static void checkNameExistence(TextField field, Label label, TaskRunner runner,
                                   String threadName, String existsKey, String newKey,
                                   Lookup<Optional<NameRecord>> findName) {
        if (field.isDisabled()) {
            return;
        }
        String name = field.getText() == null ? "" : field.getText().trim();
        if (name.isEmpty()) {
            label.setText("");
            setStatusClass(label, null);
            return;
        }

        Task<Optional<NameRecord>> task = new Task<>() {
            @Override
            protected Optional<NameRecord> call() throws SQLException {
                return findName.byHash(name);
            }
        };

        task.setOnSucceeded(e -> {
            String current = field.getText() == null ? "" : field.getText().trim();
            if (!name.equals(current)) {
                return; // user kept typing; ignore the stale result
            }
            Optional<NameRecord> existing = task.getValue();
            if (existing.isPresent()) {
                label.setText(I18n.format(existsKey, existing.get().faceCount()));
                setStatusClass(label, NAME_STATUS_EXISTS);
            } else {
                label.setText(I18n.get(newKey));
                setStatusClass(label, NAME_STATUS_NEW);
            }
        });

        task.setOnFailed(e -> {
            // name lookup failing should not block tagging
        });

        runner.start(task, threadName);
    }

    /**
     * Shows a modal error dialog owned by the given window.
     *
     * @param owner     the owner window (may be {@code null})
     * @param titleKey  i18n key for the dialog title
     * @param message   the header text
     * @param error     the underlying exception
     */
    static void showError(Window owner, String titleKey, String message, Throwable error) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(I18n.get(titleKey));
        alert.setHeaderText(message);
        alert.setContentText(error.getMessage() == null ? error.toString() : error.getMessage());
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait();
    }

    /**
     * The "Open Original" / "Open containing folder" operations every face view
     * offers, plus the context menu that presents them. Each view supplies the
     * service-backed {@link Source}, a status-bar setter and its own failure
     * handling.
     */
    static final class FaceActions {

        /**
         * The four file operations a service exposes for an image hash.
         */
        interface Source {
            boolean isOriginalAvailable(String hash) throws SQLException;

            boolean isContainingFolderAvailable(String hash) throws SQLException;

            boolean openOriginal(String hash) throws IOException, SQLException;

            boolean openContainingFolder(String hash) throws IOException, SQLException;
        }

        private final Source source;
        private final Consumer<String> status;
        private final BiFail<SQLException> availabilityFailure;
        private final BiFail<Throwable> openFailure;

        /**
         * @param source              the service-backed operations
         * @param status              sets the view's status bar text
         * @param availabilityFailure receives (hash, exception) when an
         *                            availability check fails
         * @param openFailure         receives (message, exception) when opening
         *                            a file or folder fails
         */
        FaceActions(Source source, Consumer<String> status,
                    BiFail<SQLException> availabilityFailure, BiFail<Throwable> openFailure) {
            this.source = source;
            this.status = status;
            this.availabilityFailure = availabilityFailure;
            this.openFailure = openFailure;
        }

        /**
         * Tells whether the original file of an image exists on disk.
         *
         * @param hash content hash of the image
         * @return {@code true} if the original file is available
         */
        boolean isOriginalAvailable(String hash) {
            try {
                return source.isOriginalAvailable(hash);
            } catch (SQLException ex) {
                availabilityFailure.fail(hash, ex);
                return false;
            }
        }

        /**
         * Tells whether the folder containing the original file of an image
         * exists on disk.
         *
         * @param hash content hash of the image
         * @return {@code true} if the containing folder is available
         */
        boolean isContainingFolderAvailable(String hash) {
            try {
                return source.isContainingFolderAvailable(hash);
            } catch (SQLException ex) {
                availabilityFailure.fail(hash, ex);
                return false;
            }
        }

        /**
         * Opens the original file of an image in the default viewer.
         *
         * @param hash content hash of the image
         */
        void openOriginal(String hash) {
            try {
                boolean opened = source.openOriginal(hash);
                status.accept(opened ? "" : I18n.get("common.originalNotFound"));
            } catch (IOException | SQLException ex) {
                openFailure.fail(I18n.get("common.openOriginalFailed"), ex);
            }
        }

        /**
         * Opens the folder containing the original file of an image in the file
         * manager.
         *
         * @param hash content hash of the image
         */
        void openContainingFolder(String hash) {
            try {
                boolean opened = source.openContainingFolder(hash);
                status.accept(opened ? "" : I18n.get("common.originalNotFound"));
            } catch (IOException | SQLException ex) {
                openFailure.fail(I18n.get("common.openContainingFolderFailed"), ex);
            }
        }

        /**
         * Installs the standard right-click context menu on a face thumbnail or
         * card, appending any extra items. A {@code null} face clears the menu.
         *
         * @param node   the node to attach the menu to
         * @param face   the face whose source image should be openable
         * @param extras additional menu items shown below the standard two
         */
        void installFaceMenu(Node node, FaceRecord face, MenuItem... extras) {
            if (face == null) {
                node.setOnContextMenuRequested(null);
                return;
            }
            installMenu(node, face.imageHash(), extras);
        }

        /**
         * Installs the standard right-click context menu on a node keyed by an
         * image hash, appending any extra items.
         *
         * @param node   the node to attach the menu to
         * @param hash   content hash of the image
         * @param extras additional menu items shown below the standard two
         */
        void installMenu(Node node, String hash, MenuItem... extras) {
            node.setOnContextMenuRequested(e -> {
                List<MenuItem> items = new ArrayList<>();
                items.add(menuItem(I18n.get("common.openOriginal"),
                        isOriginalAvailable(hash), () -> openOriginal(hash)));
                items.add(menuItem(I18n.get("common.openContainingFolder"),
                        isContainingFolderAvailable(hash), () -> openContainingFolder(hash)));
                items.addAll(List.of(extras));
                new ContextMenu(items.toArray(new MenuItem[0]))
                        .show(node, e.getScreenX(), e.getScreenY());
            });
        }

        private static MenuItem menuItem(String text, boolean enabled, Runnable action) {
            MenuItem item = new MenuItem(text);
            item.setDisable(!enabled);
            item.setOnAction(ev -> action.run());
            return item;
        }
    }

    /**
     * A two-argument failure callback distinct from {@link java.util.function.BiConsumer}
     * because it also needs to be usable from checked-exception contexts.
     */
    interface BiFail<T extends Throwable> {
        void fail(String subject, T error);
    }
}