package free.svoss.facesort.ui;

import free.svoss.facesort.i18n.I18n;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.service.DeduplicationService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * "Deduplicate" tab: presents the most similar name pairs one at a time so the
 * user can merge duplicates, mark pairs as distinct, or skip them.
 *
 * <p>The view owns no business logic; every decision is delegated to
 * {@link DeduplicationService}. Pair ranking can be expensive on large
 * databases, so {@link #loadNextPair()} runs on a background {@link Task}.
 * The decision handlers run on the JavaFX thread and surface database errors
 * in an error dialog.</p>
 */
public class DedupeView extends BorderPane {

    private static final Logger LOG = Logger.getLogger(DedupeView.class.getName());
    private static final double THUMBNAIL_SIZE = 200.0;

    private final DeduplicationService dedupService;

    private final Button startButton = new Button(I18n.get("ui.dedupe.start"));
    private final Button dupesButton = new Button(I18n.get("ui.dedupe.dupes"));
    private final Button notDupesButton = new Button(I18n.get("ui.dedupe.notDupes"));
    private final Button skipButton = new Button(I18n.get("ui.dedupe.skip"));
    private final Button stopButton = new Button(I18n.get("ui.dedupe.stop"));

    private final Label statusLabel = new Label(I18n.get("ui.dedupe.idleHint"));
    private final Label pairLabel = new Label();
    private final ImageView faceAView = new ImageView();
    private final ImageView faceBView = new ImageView();
    private final Label nameALabel = new Label();
    private final Label nameBLabel = new Label();

    private Task<Optional<DeduplicationService.DupeCandidate>> activeTask;
    private DeduplicationService.DupeCandidate current;
    private int round;
    private boolean sessionActive;

    /**
     * Creates the deduplication view.
     *
     * @param dedupService the deduplication service; must not be null
     */
    public DedupeView(DeduplicationService dedupService) {
        this.dedupService = dedupService;
        buildUi();
    }

    /**
     * Builds the start bar, the side-by-side pair display and the decision
     * buttons.
     */
    private void buildUi() {
        configureThumbnail(faceAView);
        configureThumbnail(faceBView);

        startButton.setOnAction(e -> startSession());

        HBox topBar = new HBox(10, startButton, statusLabel);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(10));
        statusLabel.setWrapText(true);

        pairLabel.setStyle("-fx-font-size: 14; -fx-font-weight: bold;");
        pairLabel.setPadding(new Insets(0, 0, 10, 0));
        pairLabel.setText(I18n.get("ui.dedupe.idleHint2"));

        VBox sideA = new VBox(6, faceAView, nameALabel);
        VBox sideB = new VBox(6, faceBView, nameBLabel);
        sideA.setAlignment(Pos.TOP_CENTER);
        sideB.setAlignment(Pos.TOP_CENTER);

        HBox pairBox = new HBox(30, sideA, sideB);
        pairBox.setAlignment(Pos.TOP_CENTER);

        VBox center = new VBox(10, pairLabel, pairBox);
        center.setAlignment(Pos.TOP_CENTER);
        center.setPadding(new Insets(20, 10, 10, 10));

        dupesButton.setOnAction(e -> onDupes());
        notDupesButton.setOnAction(e -> onNotDupes());
        skipButton.setOnAction(e -> onSkip());
        stopButton.setOnAction(e -> stopSession());

        HBox actions = new HBox(10, dupesButton, notDupesButton, skipButton, stopButton);
        actions.setAlignment(Pos.CENTER);
        actions.setPadding(new Insets(10));

        setTop(topBar);
        setCenter(center);
        setBottom(actions);

        setDecisionEnabled(false);
        stopButton.setDisable(true);
    }

    /**
     * Starts a fresh session: clears run state and loads the first pair.
     */
    private void startSession() {
        dedupService.reset();
        round = 0;
        sessionActive = true;
        startButton.setDisable(true);
        stopButton.setDisable(false);
        statusLabel.setText(I18n.get("ui.dedupe.preparing"));
        loadNextPair();
    }

    /**
     * Loads the next candidate pair on a background thread.
     */
    private void loadNextPair() {
        if (!sessionActive) {
            return;
        }
        setDecisionEnabled(false);

        activeTask = new Task<>() {
            @Override
            protected Optional<DeduplicationService.DupeCandidate> call() throws SQLException {
                return dedupService.nextPair();
            }
        };

        activeTask.setOnSucceeded(e -> {
            if (!sessionActive) {
                return;
            }
            Optional<DeduplicationService.DupeCandidate> next = activeTask.getValue();
            if (next.isEmpty()) {
                endSession(I18n.get("ui.dedupe.noneLeft"));
            } else {
                showPair(next.get());
            }
        });

        activeTask.setOnFailed(e -> {
            Throwable error = activeTask.getException();
            LOG.log(Level.WARNING, "Failed to load the next pair", error);
            if (sessionActive) {
                endSession(I18n.get("ui.dedupe.loadFailed"));
            }
            showError(I18n.get("ui.dedupe.loadFailed"), error);
        });

        Thread thread = new Thread(activeTask, "dedupe-pair-loader");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Displays the given pair and enables the decision buttons.
     *
     * @param candidate the pair to show
     */
    private void showPair(DeduplicationService.DupeCandidate candidate) {
        current = candidate;
        round++;
        pairLabel.setText(I18n.format("ui.dedupe.comparing",
                candidate.nameA(), candidate.nameB()));
        statusLabel.setText(I18n.format("ui.dedupe.round",
                round, candidate.nameA(), candidate.nameB(), candidate.similarity()));
        nameALabel.setText(candidate.nameA());
        nameBLabel.setText(candidate.nameB());
        faceAView.setImage(toImage(candidate.repFaceA()));
        faceBView.setImage(toImage(candidate.repFaceB()));
        installContextMenu(faceAView, candidate.repFaceA());
        installContextMenu(faceBView, candidate.repFaceB());
        setDecisionEnabled(true);
    }

    /**
     * Handles "These are dupes": asks which name survives and merges.
     */
    private void onDupes() {
        if (current == null) {
            return;
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(current.nameA(),
                current.nameA(), current.nameB());
        dialog.setTitle(I18n.get("ui.dedupe.survivorTitle"));
        dialog.setHeaderText(I18n.get("ui.dedupe.mergeHeader"));
        dialog.setContentText(I18n.format("ui.dedupe.mergeContent",
                current.nameA(), current.nameB()));

        Optional<String> choice = dialog.showAndWait();
        choice.ifPresent(survivor -> {
            boolean aSurvives = survivor.equals(current.nameA());
            long survivorId = aSurvives ? current.nameIdA() : current.nameIdB();
            long eliminatedId = aSurvives ? current.nameIdB() : current.nameIdA();
            runDecision(() -> dedupService.merge(survivorId, eliminatedId));
        });
    }

    /**
     * Handles "These are not dupes": persists a not-dupes marker.
     */
    private void onNotDupes() {
        if (current == null) {
            return;
        }
        DeduplicationService.DupeCandidate pair = current;
        runDecision(() -> dedupService.markNotDupes(pair.nameIdA(), pair.nameIdB()));
    }

    /**
     * Handles "Skip": hides the pair for the rest of this session.
     */
    private void onSkip() {
        if (current == null) {
            return;
        }
        dedupService.skip(current.nameIdA(), current.nameIdB());
        loadNextPair();
    }

    /**
     * Runs a decision and advances to the next pair. The current pair is
     * cleared only after the decision succeeds.
     *
     * @param action the database operation to perform
     */
    private void runDecision(Decision action) {
        setDecisionEnabled(false);
        try {
            action.run();
            loadNextPair();
        } catch (SQLException ex) {
            LOG.log(Level.WARNING, "Deduplication decision failed", ex);
            showError(I18n.get("ui.dedupe.operationFailed"), ex);
            setDecisionEnabled(true);
        }
    }

    /**
     * Stops the session: cancels any running load and hides the pair UI.
     */
    private void stopSession() {
        sessionActive = false;
        current = null;
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
        endSession(I18n.get("ui.dedupe.sessionStopped"));
    }

    /**
     * Tears down the pair UI and returns to the idle state.
     *
     * @param message the message shown to the user
     */
    private void endSession(String message) {
        sessionActive = false;
        current = null;
        statusLabel.setText(message);
        pairLabel.setText(I18n.get("ui.dedupe.newSessionHint"));
        nameALabel.setText("");
        nameBLabel.setText("");
        faceAView.setImage(null);
        faceBView.setImage(null);
        installContextMenu(faceAView, null);
        installContextMenu(faceBView, null);
        setDecisionEnabled(false);
        stopButton.setDisable(true);
        startButton.setDisable(false);
    }

    /**
     * Enables or disables the decision buttons.
     *
     * @param enabled {@code true} to enable the decision buttons
     */
    private void setDecisionEnabled(boolean enabled) {
        dupesButton.setDisable(!enabled);
        notDupesButton.setDisable(!enabled);
        skipButton.setDisable(!enabled);
    }

    /**
     * Decodes a face's JPEG sub-image into a JavaFX image, or returns
     * {@code null} when no sub-image is available.
     *
     * @param face the face record to render
     * @return the decoded image, or {@code null} when unavailable
     */
    private static Image toImage(FaceRecord face) {
        byte[] jpg = face != null ? face.subImageJpg() : null;
        if (jpg == null || jpg.length == 0) {
            return null;
        }
        return new Image(new ByteArrayInputStream(jpg));
    }

    /**
     * Configures a face thumbnail ImageView to a fixed display size.
     *
     * @param view the ImageView to configure
     */
    private static void configureThumbnail(ImageView view) {
        view.setFitWidth(THUMBNAIL_SIZE);
        view.setFitHeight(THUMBNAIL_SIZE);
        view.setPreserveRatio(true);
        view.setSmooth(true);
    }

    /**
     * Installs a right-click context menu on a face thumbnail with an "Open
     * Original" entry, grayed out while the source image is not available on
     * disk.
     *
     * @param node the node to attach the menu to
     * @param face the face whose source image should be openable
     */
    private void installContextMenu(javafx.scene.Node node, FaceRecord face) {
        if (face == null) {
            node.setOnContextMenuRequested(null);
            return;
        }
        node.setOnContextMenuRequested(e -> {
            MenuItem openOriginalItem = new MenuItem(I18n.get("common.openOriginal"));
            openOriginalItem.setDisable(!isOriginalAvailable(face));
            openOriginalItem.setOnAction(ev -> openOriginal(face.imageHash()));
            new ContextMenu(openOriginalItem).show(node, e.getScreenX(), e.getScreenY());
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
            return dedupService.isOriginalAvailable(face.imageHash());
        } catch (SQLException ex) {
            LOG.log(Level.WARNING, "Could not check original availability for "
                    + face.imageHash(), ex);
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
            boolean opened = dedupService.openOriginal(hash);
            statusLabel.setText(opened ? "" : I18n.get("common.originalNotFound"));
        } catch (IOException | SQLException ex) {
            LOG.log(Level.WARNING, "Could not open original image " + hash, ex);
            showError(I18n.get("common.openOriginalFailed"), ex);
        }
    }

    /**
     * Shows a modal error dialog owned by this view's window.
     *
     * @param message the header text
     * @param error   the underlying exception
     */
    private void showError(String message, Throwable error) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(I18n.get("ui.dedupe.alertTitle"));
        alert.setHeaderText(message);
        alert.setContentText(error.getMessage() == null ? error.toString() : error.getMessage());
        Window window = getScene() != null ? getScene().getWindow() : null;
        if (window != null) {
            alert.initOwner(window);
        }
        alert.showAndWait();
    }

    /**
     * A database decision that may fail with {@link SQLException}.
     */
    @FunctionalInterface
    private interface Decision {
        void run() throws SQLException;
    }
}