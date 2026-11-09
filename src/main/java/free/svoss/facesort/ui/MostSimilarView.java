package free.svoss.facesort.ui;

import free.svoss.facesort.i18n.I18n;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.service.FaceToNameService;
import free.svoss.facesort.service.MostSimilarQueue;
import free.svoss.facesort.service.MostSimilarService;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The "Most similar" tab.
 *
 * <p>Shows the single (name, unnamed face) pair with the highest similarity in
 * the whole library and asks the user whether the two faces are the same
 * person. "Same person" tags the unnamed face with the name, "Not the same
 * person" records the rejection so neither naming tab offers that combination
 * again, and "Skip" hides the pair for the current session only. After every
 * decision the next best pair is loaded, usually straight from the look-ahead
 * queue that was filled while the user was reviewing the previous pair.</p>
 *
 * <p>The view owns no business logic; selecting the pair and applying the
 * decisions are delegated to {@link MostSimilarService}, the look-ahead queue
 * lives in {@link MostSimilarQueue}, and the face context menu is backed by
 * {@link FaceToNameService}. Queries run on background {@link Task}s so the UI
 * stays responsive.</p>
 */
public class MostSimilarView extends BorderPane implements Refreshable {

    private static final double THUMBNAIL_SIZE = 170.0;
    private static final double PLACEHOLDER_MAX_WIDTH = 420.0;
    private static final double BATCH_THUMBNAIL_SIZE = 95.0;
    private static final int BATCH_SIZE = 12;
    private static final String LOADER_THREAD = "most-similar-loader";
    private static final String PREFETCH_THREAD = "most-similar-prefetcher";
    private static final String BATCH_THREAD = "most-similar-batch";

    private final MostSimilarService mostSimilarService;
    private final FaceToNameService faceToNameService;
    private final MostSimilarQueue queue;

    private final Label titleLabel = new Label();
    private final Label similarityLabel = new Label();
    private final Label faceSimilarityLabel = new Label();
    private final VBox pairDetails = new VBox(8, titleLabel, similarityLabel, faceSimilarityLabel);
    private final HBox pairBox = new HBox(24);
    private final Button sameButton = new Button(I18n.get("ui.mostSimilar.same"));
    private final Button differentButton = new Button(I18n.get("ui.mostSimilar.different"));
    private final Button skipButton = new Button(I18n.get("ui.mostSimilar.skip"));
    private final Label statusLabel = new Label("");

    // Batch review mode
    private final ToggleButton batchToggle = new ToggleButton(I18n.get("ui.mostSimilar.batchMode"));
    private final FlowPane batchGrid = new FlowPane(12, 12);
    private final ScrollPane batchScroll = new ScrollPane(batchGrid);
    private final Button tagBatchButton = new Button(I18n.get("ui.mostSimilar.tagBatch"));
    private final Button rejectBatchButton = new Button(I18n.get("ui.mostSimilar.rejectBatch"));
    private final Button skipAllButton = new Button(I18n.get("ui.mostSimilar.skipAll"));
    private final HBox singleButtons = new HBox(10);
    private final HBox batchButtons = new HBox(10);
    private final VBox singleBottom = new VBox();
    private final VBox batchBottom = new VBox();

    private boolean batchMode = false;
    private List<MostSimilarService.Suggestion> batchSuggestions = List.of();
    private final Set<Long> selectedFaces = new HashSet<>();

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
        this.queue = new MostSimilarQueue(mostSimilarService);
        this.faceActions = FaceUi.faceActions(faceToNameService, statusLabel::setText,
                this::handleFailure);
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

        batchToggle.setOnAction(e -> setBatchMode(batchToggle.isSelected()));
        HBox modeBar = new HBox(10, batchToggle);
        modeBar.setAlignment(Pos.CENTER_LEFT);
        modeBar.setPadding(new Insets(10, 10, 0, 10));

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
        HBox singleButtons = new HBox(10, sameButton, differentButton, skipButton);
        singleButtons.setAlignment(Pos.CENTER);
        singleButtons.setPadding(new Insets(10));

        tagBatchButton.getStyleClass().add("primary");
        tagBatchButton.setOnAction(e -> onTagBatch());
        rejectBatchButton.setOnAction(e -> onRejectBatch());
        skipAllButton.setOnAction(e -> onSkipAll());
        batchButtons.getChildren().addAll(tagBatchButton, rejectBatchButton, skipAllButton);
        batchButtons.setAlignment(Pos.CENTER);
        batchButtons.setPadding(new Insets(10));

        batchGrid.setPadding(new Insets(10));
        batchScroll.setFitToWidth(true);

        singleButtons.getChildren().addAll(sameButton, differentButton, skipButton);
        singleButtons.setAlignment(Pos.CENTER);
        singleButtons.setPadding(new Insets(10));

        singleBottom.getChildren().addAll(singleButtons, statusLabel);
        batchBottom.getChildren().addAll(batchButtons, statusLabel);

        statusLabel.setWrapText(true);
        statusLabel.setPadding(new Insets(0, 10, 10, 10));

        setTop(new VBox(introLabel, modeBar));
        setCenter(pairBox);
        setBottom(singleBottom);
        setBusy(true);
    }

    /**
     * Switches between the single-pair view and the batch review grid.
     *
     * @param batch {@code true} to show the batch grid, {@code false} for the
     *              single pair
     */
    private void setBatchMode(boolean batch) {
        batchMode = batch;
        batchGrid.getChildren().clear();
        selectedFaces.clear();
        updateBatchButtonState();
        if (batch) {
            setCenter(batchScroll);
            setBottom(batchBottom);
            loadBatchSuggestions();
        } else {
            setCenter(pairBox);
            setBottom(singleBottom);
            loadNextSuggestion();
        }
    }

    /**
     * Enables or disables the batch action buttons depending on whether any
     * card is selected.
     */
    private void updateBatchButtonState() {
        boolean anySelected = !selectedFaces.isEmpty();
        tagBatchButton.setDisable(batchMode && !anySelected);
        rejectBatchButton.setDisable(batchMode && !anySelected);
        skipAllButton.setDisable(batchMode && !anySelected);
    }

    /**
     * Loads the current best pair in the background and renders it. Called on
     * first show, after every decision and whenever the tab is re-selected.
     */
    @Override
    public void refresh() {
        queue.clear();
        if (batchMode) {
            loadBatchSuggestions();
        } else {
            loadNextSuggestion();
        }
    }

    /**
     * Shows the next prefetched pair, or loads one when the queue is empty, and
     * refills the queue in the background. The loading placeholder appears only
     * when no pair is ready. No-op in batch mode.
     */
    private void loadNextSuggestion() {
        if (batchMode) {
            return;
        }
        taskRunner.cancel(PREFETCH_THREAD);
        boolean queued = queue.hasQueued();
        setBusy(true);
        if (!queued) {
            pairBox.getChildren().clear();
            showPlaceholder(I18n.get("ui.mostSimilar.loading"));
        }

        Task<Optional<MostSimilarService.Suggestion>> task = new Task<>() {
            @Override
            protected Optional<MostSimilarService.Suggestion> call() throws SQLException {
                return queue.next();
            }
        };

        task.setOnSucceeded(e -> {
            showSuggestion(task.getValue());
            prefetchNext();
        });
        task.setOnFailed(e -> {
            setBusy(false);
            handleFailure(I18n.get("ui.mostSimilar.loadFailed"), task.getException());
        });

        taskRunner.start(task, LOADER_THREAD);
    }

    /**
     * Refills the look-ahead queue while the user looks at the current pair.
     * A failure only updates the status bar; it must not interrupt the review.
     */
    private void prefetchNext() {
        if (current == null) {
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws SQLException {
                queue.prefetch();
                return null;
            }
        };
        task.setOnFailed(e -> statusLabel.setText(I18n.get("ui.mostSimilar.loadFailed")));
        taskRunner.start(task, PREFETCH_THREAD);
    }

    /**
     * Renders the given pair, or the empty state when there is none. No-op in
     * batch mode.
     *
     * @param suggestion the pair to show, or empty when nothing is left
     */
    private void showSuggestion(Optional<MostSimilarService.Suggestion> suggestion) {
        if (batchMode) {
            return;
        }
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
        placeholder.setMaxWidth(PLACEHOLDER_MAX_WIDTH);
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
                queue::clear,
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
                () -> queue.forgetPair(suggestion.candidate().id(), suggestion.name().id()),
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
        taskRunner.cancel(PREFETCH_THREAD);
        mostSimilarService.skip(suggestion.candidate().id(), suggestion.name().id());
        queue.forgetPair(suggestion.candidate().id(), suggestion.name().id());
        statusLabel.setText(I18n.get("ui.mostSimilar.skipped"));
        loadNextSuggestion();
    }

    /**
     * Loads the top {@link #BATCH_SIZE} pairs in the background and renders
     * them as a selectable grid for batch review.
     */
    private void loadBatchSuggestions() {
        setBusy(true);
        batchGrid.getChildren().clear();
        selectedFaces.clear();
        statusLabel.setText(I18n.get("ui.mostSimilar.loadingBatch"));

        Task<List<MostSimilarService.Suggestion>> task = new Task<>() {
            @Override
            protected List<MostSimilarService.Suggestion> call() throws SQLException {
                return mostSimilarService.findBestSuggestions(BATCH_SIZE);
            }
        };

        task.setOnSucceeded(e -> {
            batchSuggestions = task.getValue();
            renderBatch(batchSuggestions);
            setBusy(false);
            if (batchSuggestions.isEmpty()) {
                statusLabel.setText(I18n.get("ui.mostSimilar.empty"));
            } else {
                statusLabel.setText(I18n.format("ui.mostSimilar.batchCount", batchSuggestions.size()));
            }
        });
        task.setOnFailed(e -> {
            setBusy(false);
            handleFailure(I18n.get("ui.mostSimilar.loadFailed"), task.getException());
        });

        taskRunner.start(task, BATCH_THREAD);
    }

    /**
     * Renders the given pairs as batch cards, each with a checkbox for
     * selection.
     *
     * @param suggestions the pairs to render
     */
    private void renderBatch(List<MostSimilarService.Suggestion> suggestions) {
        batchGrid.getChildren().clear();
        for (MostSimilarService.Suggestion suggestion : suggestions) {
            batchGrid.getChildren().add(batchCard(suggestion));
        }
        updateBatchButtonState();
    }

    /**
     * Builds one batch card: a checkbox, the tagged face with its name, the
     * unnamed candidate, and both similarity scores.
     *
     * @param suggestion the pair to render
     * @return the card node
     */
    private VBox batchCard(MostSimilarService.Suggestion suggestion) {
        VBox card = new VBox(6);
        card.setAlignment(Pos.TOP_CENTER);
        card.setPadding(new Insets(6));
        card.getStyleClass().add("candidate-card");

        CheckBox select = new CheckBox();
        select.setOnAction(e -> {
            if (select.isSelected()) {
                selectedFaces.add(suggestion.candidate().id());
            } else {
                selectedFaces.remove(suggestion.candidate().id());
            }
            updateBatchButtonState();
        });

        ImageView taggedThumb = FaceUi.thumb(suggestion.tagged(), BATCH_THUMBNAIL_SIZE);
        ImageView candidateThumb = FaceUi.thumb(suggestion.candidate(), BATCH_THUMBNAIL_SIZE);

        Label nameLabel = new Label(suggestion.name().name());
        nameLabel.getStyleClass().add("secondary-text");
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(BATCH_THUMBNAIL_SIZE);

        Label simLabel = new Label(I18n.percent(suggestion.nameSimilarity()));
        simLabel.getStyleClass().add("secondary-text");

        card.getChildren().addAll(select, taggedThumb, nameLabel, candidateThumb, simLabel);
        return card;
    }

    /**
     * Applies the "same person" decision to every selected pair in a single
     * transaction, then reloads the batch.
     */
    private void onTagBatch() {
        if (batchSuggestions.isEmpty() || selectedFaces.isEmpty()) {
            return;
        }
        List<MostSimilarService.Suggestion> selected = batchSuggestions.stream()
                .filter(s -> selectedFaces.contains(s.candidate().id()))
                .toList();

        setBusy(true);
        statusLabel.setText(I18n.format("ui.mostSimilar.taggingBatch", selected.size()));

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws SQLException {
                mostSimilarService.tagSelected(selected);
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            selectedFaces.clear();
            statusLabel.setText(I18n.format("ui.mostSimilar.taggedBatch", selected.size()));
            loadBatchSuggestions();
        });

        task.setOnFailed(e -> {
            setBusy(false);
            handleFailure(I18n.get("ui.mostSimilar.tagFailed"), task.getException());
        });

        taskRunner.start(task, "most-similar-batch-tagger");
    }

    /**
     * Records that every selected candidate is not its suggested person, in a
     * single transaction, then reloads the batch.
     */
    private void onRejectBatch() {
        if (batchSuggestions.isEmpty() || selectedFaces.isEmpty()) {
            return;
        }
        List<MostSimilarService.Suggestion> selected = batchSuggestions.stream()
                .filter(s -> selectedFaces.contains(s.candidate().id()))
                .toList();

        setBusy(true);
        statusLabel.setText(I18n.format("ui.mostSimilar.rejectingBatch", selected.size()));

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws SQLException {
                mostSimilarService.rejectSelected(selected);
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            selectedFaces.clear();
            statusLabel.setText(I18n.format("ui.mostSimilar.rejectedBatch", selected.size()));
            loadBatchSuggestions();
        });

        task.setOnFailed(e -> {
            setBusy(false);
            handleFailure(I18n.get("ui.mostSimilar.rejectFailed"), task.getException());
        });

        taskRunner.start(task, "most-similar-batch-rejecter");
    }

    /**
     * Hides every selected pair for the current session only, then reloads the
     * batch.
     */
    private void onSkipAll() {
        if (batchSuggestions.isEmpty() || selectedFaces.isEmpty()) {
            return;
        }
        List<MostSimilarService.Suggestion> selected = batchSuggestions.stream()
                .filter(s -> selectedFaces.contains(s.candidate().id()))
                .toList();

        mostSimilarService.skipAll(selected);
        selectedFaces.clear();
        statusLabel.setText(I18n.format("ui.mostSimilar.skippedBatch", selected.size()));
        loadBatchSuggestions();
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
                () -> queue.forgetCandidate(faceId),
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
     * @param invalidate     removes queued pairs the decision made stale
     * @param threadName     name of the daemon thread running the action
     */
    private void runDecision(String failureKey, String successMessage,
                             SqlAction action, Runnable invalidate, String threadName) {
        taskRunner.cancel(PREFETCH_THREAD);
        setBusy(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws SQLException {
                action.run();
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            invalidate.run();
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
        if (batchMode) {
            tagBatchButton.setDisable(busy || selectedFaces.isEmpty());
            rejectBatchButton.setDisable(busy || selectedFaces.isEmpty());
            skipAllButton.setDisable(busy || selectedFaces.isEmpty());
            return;
        }
        boolean disabled = busy || current == null;
        sameButton.setDisable(disabled);
        differentButton.setDisable(disabled);
        skipButton.setDisable(disabled);
    }

    private void handleFailure(String message, Throwable error) {
        FaceUi.handleFailure(statusLabel, this, "ui.mostSimilar.alertTitle", message, error);
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
