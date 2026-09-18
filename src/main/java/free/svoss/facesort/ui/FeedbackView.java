package free.svoss.facesort.ui;

import free.svoss.facesort.service.FeedbackService;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * The Feedback tab: lets the user send feedback to the developers via
 * web3forms.com.
 *
 * <p>The user picks a feedback type, writes a message of at least
 * {@value #MIN_FEEDBACK_LENGTH} characters, and submits it on a background
 * thread so the UI stays responsive. On success the message area is cleared and
 * the status line confirms the submission; on failure the text is kept so it
 * can be edited and sent again.</p>
 */
public class FeedbackView extends BorderPane {

    /** Minimum number of characters required before a message becomes submittable. */
    public static final int MIN_FEEDBACK_LENGTH = 10;

    private static final String[] FEEDBACK_TYPES = {
            "Report an error",
            "Feature request",
            "Other"
    };

    private final FeedbackService feedbackService;

    private final ComboBox<String> typeBox = new ComboBox<>();
    private final TextArea feedbackText = new TextArea();
    private final Button submitButton = new Button("Submit");
    private final Label statusLabel = new Label();

    private boolean sending;

    /**
     * Creates the Feedback tab.
     */
    public FeedbackView() {
        this(new FeedbackService());
    }

    /**
     * Creates the Feedback tab backed by the given service.
     *
     * @param feedbackService the feedback submission service; must not be null
     */
    FeedbackView(FeedbackService feedbackService) {
        this.feedbackService = Objects.requireNonNull(feedbackService, "feedbackService");
        typeBox.getItems().addAll(FEEDBACK_TYPES);
        typeBox.getSelectionModel().selectFirst();
        feedbackText.textProperty().addListener((obs, oldText, newText) -> updateSubmitAvailability());
        buildUi();
        updateSubmitAvailability();
    }

    /**
     * Builds the type picker, message area, submit button and status line.
     */
    private void buildUi() {
        Label typeLabel = new Label("Feedback type:");
        typeBox.setMaxWidth(Double.MAX_VALUE);

        Label messageLabel = new Label("Your feedback:");
        feedbackText.setPromptText("Enter at least " + MIN_FEEDBACK_LENGTH + " characters...");
        feedbackText.setWrapText(true);
        feedbackText.setPrefRowCount(10);
        VBox.setVgrow(feedbackText, Priority.ALWAYS);

        submitButton.setDefaultButton(true);
        submitButton.setOnAction(e -> onSubmit());

        statusLabel.setWrapText(true);

        VBox form = new VBox(8, typeLabel, typeBox, messageLabel, feedbackText);
        form.setPadding(new Insets(10));

        HBox actions = new HBox(8, submitButton, statusLabel);
        actions.setPadding(new Insets(0, 10, 10, 10));
        actions.setAlignment(Pos.CENTER_LEFT);

        setCenter(form);
        setBottom(actions);
    }

    /**
     * Enables the submit button only when a message of at least
     * {@link #MIN_FEEDBACK_LENGTH} characters is present and no submission is
     * currently running.
     */
    private void updateSubmitAvailability() {
        String text = feedbackText.getText();
        boolean tooShort = text == null || text.trim().length() < MIN_FEEDBACK_LENGTH;
        submitButton.setDisable(sending || tooShort);
    }

    /**
     * Submits the current feedback on a background thread.
     */
    private void onSubmit() {
        String selected = typeBox.getSelectionModel().getSelectedItem();
        String subject = selected == null ? FEEDBACK_TYPES[0] : selected;
        String message = feedbackText.getText().trim();
        if (message.length() < MIN_FEEDBACK_LENGTH) {
            return;
        }

        sending = true;
        updateSubmitAvailability();
        statusLabel.setText("Submitting feedback...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                feedbackService.submit(subject, message);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            sending = false;
            feedbackText.clear();
            statusLabel.setText("Your feedback was submitted");
            updateSubmitAvailability();
        });
        task.setOnFailed(e -> {
            sending = false;
            updateSubmitAvailability();
            Throwable error = task.getException();
            statusLabel.setText("Submission failed: "
                    + (error == null ? "unknown error" : error.getMessage()));
        });

        Thread thread = new Thread(task, "feedback-submit");
        thread.setDaemon(true);
        thread.start();
    }
}