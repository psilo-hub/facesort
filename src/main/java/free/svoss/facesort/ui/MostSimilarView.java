package free.svoss.facesort.ui;

import free.svoss.facesort.i18n.I18n;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.service.FaceToNameService;
import free.svoss.facesort.service.MostSimilarService;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.sql.SQLException;
import java.util.Optional;

/**
 * The "Most similar" tab.
 *
 * <p>Shows the single (name, unnamed face) pair with the highest similarity in
 * the whole library and asks the user whether the two faces are the same
 * person. "Same person" tags the unnamed face with the name, "Not the same
 * person" records the rejection so neither naming tab offers that combination
 * again, and "Skip" hides the pair for the current session only. After every
 * decision the next best pair is loaded.</p>
 *
 * <p>The view owns no business logic; selecting the pair and applying the
 * decisions are delegated to {@link MostSimilarService}, and the face context
 * menu to {@link FaceToNameService}. Queries run on background {@link Task}s so
 * the UI stays responsive.</p>
 */
public class MostSimilarView extends BorderPane implements Refreshable {

    private static final double THUMBNAIL_SIZE = 170.0;

    private final MostSimilarService mostSimilarService;
    private final FaceToNameService faceToNameService;

    private final Label titleLabel = new Label();
    private final Label similarityLabel = new Label();
    private final Label faceSimilarityLabel = new Label();
    private final VBox pairDetails = new VBox(8, titleLabel, similarityLabel, faceSimilarityLabel);
    private final HBox pairBox = new HBox(24);
    private final Button sameButton = new Button(I18n.get("ui.mostSimilar.same"));
    private final Button differentButton = new Button(I18n.get("ui.mostSimilar.different"));
    private final Button skipButton = new Button(I18n.get("ui.mostSimilar.skip"));
    private final Label statusLabel = new Label("");

    private final TaskRunner taskRunner = new TaskRunner();
    private final FaceUi.FaceActions faceActions;

    /** The pair currently shown, or {@code null} while loading or none is left. */
    private MostSimilarService.Suggestion current;

    /**
     * Creates the most-similar tab.
     *
     * @param mostSimilarService the service that picks the pair and applies the
     *                           decisions; must not be null
     * @param faceToNameService  the service backing the face context menu;
     *                           must not be null
     */
    public MostSimilarView(MostSimilarService mostSimilarService, FaceToNameService faceToNameService) {
        this.mostSimilarService = mostSimilarService;
        this.faceToNameService = faceToNameService;
        this.faceActions = new FaceUi.FaceActions(
                new FaceUi.FaceActions.Source() {
                    @Override
                    public boolean isOriginalAvailable(String hash) throws SQLException {
                        return faceToNameService.isOriginalAvailable(hash);
                    }

                    @Override
                    public boolean isContainingFolderAvailable(String hash) throws SQLException {
                        return faceToNameService.isContainingFolderAvailable(hash);
                    }

                    @Override
                    public boolean openOriginal(String hash) throws java.io.IOException, SQLException {
                        return faceToNameService.openOriginal(hash);
                    }

                    @Override
                    public boolean openContainingFolder(String hash) throws java.io.IOException, SQLException {
                        return faceToNameService.openContainingFolder(hash);
                    }
                },
                statusLabel::setText,
                (hash, ex) -> statusLabel.setText(I18n.get("common.originalCheckFailed")),
                (message, ex) -> {
                    statusLabel.setText(message);
                    handleFailure(message, ex);
                });
        buildUi();
        loadNextSuggestion();
    }

    /**
     * Builds the instruction header, the two-card pair area and the decision
     * buttons with the status bar.
     */
    private void buildUi() {
        Label introLabel = new Label(I18n.get("ui.mostSimilar.intro"));
        introLabel.setWrapText(true);
        introLabel.setPadding(new Insets(10, 10, 0, 10));

        titleLabel.getStyleClass().add("frame-title");
        similarityLabel.getStyleClass().add("secondary-text");
        faceSimilarityLabel.getStyleClass().add("secondary-text");
        pairDetails.setAlignment(Pos.CENTER);
        pairDetails.setMinWidth(280);

        pairBox.setAlignment(Pos.CENTER);
        pairBox.setPadding(new Insets(20));

        sameButton.getStyleClass().add("primary");
        sameButton.setOnAction(e -> onSamePerson());
        differentButton.setOnAction(e -> onDifferentPerson());
        skipButton.setOnAction(e -> onSkip());
        HBox buttons = new HBox(10, sameButton, differentButton, skipButton);
        buttons.setAlignment(Pos.CENTER);
        buttons.setPadding(new Insets(10));

        statusLabel.setWrapText(true);
        statusLabel.setPadding(new Insets(0, 10, 10, 10));

        setTop(introLabel);
        setCenter(pairBox);
        setBottom(new VBox(buttons, statusLabel));
        setBusy(true);
    }

    /**
     * Loads the current best pair in the background and renders it. Called on
     * first show, after every decision and whenever the tab is re-selected.
     */
    @Override
    public void refresh() {
        loadNextSuggestion();
    }

    /**
     * Loads the pair with the highest similarity that is still undecided and
     * renders it, or shows the empty state when nothing is left.
     */
    private void loadNextSuggestion() {
        setBusy(true);
        pairBox.getChildren().clear();
        showPlaceholder(I18n.get("ui.mostSimilar.loading"));

        Task<Optional<MostSimilarService.Suggestion>> task = new Task<>() {
            @Override
            protected Optional<MostSimilarService.Suggestion> call() throws SQLException {
                return mostSimilarService.findBestSuggestion();
            }
        };

        task.setOnSucceeded(e -> showSuggestion(task.getValue()));
        task.setOnFailed(e -> {
            setBusy(false);
            handleFailure(I18n.get("ui.mostSimilar.loadFailed"), task.getException());
        });

        taskRunner.start(task, "most-similar-loader");
    }

    /**
     * Renders the given pair, or the empty state when there is none.
     *
     * @param suggestion the pair to show, or empty when nothing is left
     */
    private void showSuggestion(Optional<MostSimilarService.Suggestion> suggestion) {
        current = suggestion.orElse(null);
        pairBox.getChildren().clear();
        if (current == null) {
            showPlaceholder(I18n.get("ui.mostSimilar.empty"));
            statusLabel.setText(I18n.get("ui.mostSimilar.empty"));
            setBusy(false);
            return;
        }

        pairBox.getChildren().addAll(
                faceCard(current.tagged(), I18n.format("ui.mostSimilar.taggedCaption", current.name().name())),
                pairDetails,
                faceCard(current.candidate(), I18n.get("ui.mostSimilar.candidateCaption")));
        titleLabel.setText(I18n.format("ui.mostSimilar.decideFor", current.name().name()));
        similarityLabel.setText(I18n.format("ui.mostSimilar.similarity",
                current.name().name(), I18n.percent(current.nameSimilarity())));
        faceSimilarityLabel.setText(I18n.format("ui.mostSimilar.faceSimilarity",
                I18n.percent(current.faceSimilarity())));
        statusLabel.setText(I18n.get("ui.mostSimilar.prompt"));
        setBusy(false);
    }

    /**
     * Builds a face card with a caption below the thumbnail and the standard
     * context menu.
     *
     * @param face    the face to render
     * @param caption the caption shown under the thumbnail
     * @return the card node
     */
    private VBox faceCard(FaceRecord face, String caption) {
        VBox card = FaceUi.faceCard(face, THUMBNAIL_SIZE);
        Label captionLabel = new Label(caption);
        captionLabel.getStyleClass().add("secondary-text");
        captionLabel.setWrapText(true);
        card.getChildren().add(captionLabel);
        card.setOnContextMenuRequested(e -> {
            MenuItem notAFaceItem = new MenuItem(I18n.get("common.notAFace"));
            notAFaceItem.setOnAction(ev -> onMarkNotAFace(face.id()));
            faceActions.installFaceMenu(card, face, notAFaceItem);
        });
        return card;
    }

    /**
     * Shows a centered placeholder in the pair area.
     *
     * @param message the placeholder text
     */
    private void showPlaceholder(String message) {
        Label placeholder = new Label(message);
        placeholder.getStyleClass().add("secondary-text");
        placeholder.setWrapText(true);
        placeholder.setMaxWidth(420);
        pairBox.getChildren().add(placeholder);
    }

    /**
     * Applies the "same person" decision: the unnamed face is tagged with the
     * name, then the next pair is loaded.
     */
    private void onSamePerson() {
        MostSimilarService.Suggestion suggestion = current;
        if (suggestion == null) {
            return;
        }
        runDecision("ui.mostSimilar.tagFailed",
                I18n.format("ui.mostSimilar.tagged", suggestion.name().name()),
                () -> mostSimilarService.markSamePerson(
                        suggestion.candidate().id(), suggestion.name().id()),
                "most-similar-tagger");
    }

    /**
     * Applies the "not the same person" decision: the rejection is stored, then
     * the next pair is loaded.
     */
    private void onDifferentPerson() {
        MostSimilarService.Suggestion suggestion = current;
        if (suggestion == null) {
            return;
        }
        runDecision("ui.mostSimilar.rejectFailed",
                I18n.format("ui.mostSimilar.rejected", suggestion.name().name()),
                () -> mostSimilarService.markDifferentPerson(
                        suggestion.candidate().id(), suggestion.name().id()),
                "most-similar-rejecter");
    }

    /**
     * Applies the "skip" decision: the pair is hidden for this session only and
     * the next pair is loaded immediately.
     */
    private void onSkip() {
        MostSimilarService.Suggestion suggestion = current;
        if (suggestion == null) {
            return;
        }
        mostSimilarService.skip(suggestion.candidate().id(), suggestion.name().id());
        statusLabel.setText(I18n.get("ui.mostSimilar.skipped"));
        loadNextSuggestion();
    }

    /**
     * Records that the given face is not a face after all, so it is left out of
     * every naming tab from now on, then loads the next pair.
     *
     * @param faceId id of the face to dismiss
     */
    private void onMarkNotAFace(long faceId) {
        runDecision("common.notAFaceFailed",
                I18n.get("common.notAFaceMarked"),
                () -> faceToNameService.markNotAFace(faceId),
                "most-similar-not-a-face");
    }

    /**
     * Runs a decision in the background and loads the next pair on success. On
     * failure the status bar and an error dialog report the problem and the
     * current pair stays on screen.
     *
     * @param failureKey     message key used as the dialog header when the action fails
     * @param successMessage status text shown while the next pair loads
     * @param action         the database action to run
     * @param threadName     name of the daemon thread running the action
     */
    private void runDecision(String failureKey, String successMessage,
                             SqlAction action, String threadName) {
        setBusy(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws SQLException {
                action.run();
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            statusLabel.setText(successMessage);
            loadNextSuggestion();
        });

        task.setOnFailed(e -> {
            setBusy(false);
            handleFailure(I18n.get(failureKey), task.getException());
        });

        taskRunner.start(task, threadName);
    }

    /**
     * Enables or disables the decision buttons while a background operation runs
     * or no pair is on screen.
     *
     * @param busy {@code true} while a background operation runs
     */
    private void setBusy(boolean busy) {
        boolean disabled = busy || current == null;
        sameButton.setDisable(disabled);
        differentButton.setDisable(disabled);
        skipButton.setDisable(disabled);
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
        FaceUi.showError(window, "ui.mostSimilar.alertTitle", message, error);
    }

    /** A database action that may fail with an {@link SQLException}. */
    @FunctionalInterface
    private interface SqlAction {

        /**
         * Runs the action.
         *
         * @throws SQLException on database error
         */
        void run() throws SQLException;
    }
}
