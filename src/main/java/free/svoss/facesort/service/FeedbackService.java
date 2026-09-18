package free.svoss.facesort.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Submits user feedback to web3forms.com.
 *
 * <p>The access key is a public key intended for client-side use, so embedding
 * it in the client application is safe. Feedback is sent as an
 * {@code application/json} POST, which is the content type web3forms requires
 * for programmatic submissions so that it answers with a JSON payload; a
 * submission is only treated as successful when the endpoint replies with
 * HTTP 200 and a body whose {@code success} flag is {@code true}.</p>
 */
public class FeedbackService {

    /** web3forms submission endpoint. */
    public static final String SUBMIT_URL = "https://api.web3forms.com/submit";

    private static final String ACCESS_KEY = "84f96b52-64e6-488a-910f-cb597637439f";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final String submitUrl;

    /**
     * Creates a feedback service that posts to the web3forms endpoint.
     */
    public FeedbackService() {
        this(HttpClient.newHttpClient(), SUBMIT_URL);
    }

    /**
     * Creates a feedback service for tests.
     *
     * @param httpClient the HTTP client to use; must not be null
     * @param submitUrl  the submission endpoint to post to; must not be null
     */
    FeedbackService(HttpClient httpClient, String submitUrl) {
        this.httpClient = httpClient;
        this.submitUrl = submitUrl;
    }

    /**
     * Submits a feedback message.
     *
     * @param type    the feedback type, used as the subject
     * @param message the feedback text
     * @throws IOException         if the message cannot be sent
     * @throws SubmissionException if the endpoint rejects the submission
     */
    public void submit(String type, String message) throws IOException, SubmissionException {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("access_key", ACCESS_KEY);
        payload.put("botcheck", "");
        payload.put("subject", type);
        payload.put("message", message);

        HttpRequest request = HttpRequest.newBuilder(URI.create(submitUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while submitting feedback", e);
        }

        if (response.statusCode() != 200 || !isSuccess(response.body())) {
            String detail = serverMessage(response.body());
            throw new SubmissionException("web3forms rejected the submission (HTTP "
                    + response.statusCode() + ")" + (detail.isEmpty() ? "" : ": " + detail));
        }
    }

    private static boolean isSuccess(String jsonBody) {
        try {
            JsonNode root = JSON.readTree(jsonBody);
            return root.path("success").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String serverMessage(String jsonBody) {
        try {
            JsonNode root = JSON.readTree(jsonBody);
            JsonNode rootMessage = root.path("message");
            if (rootMessage.isTextual()) {
                return rootMessage.asText();
            }
            JsonNode bodyMessage = root.path("body").path("message");
            if (bodyMessage.isTextual()) {
                return bodyMessage.asText();
            }
            return "";
        } catch (Exception e) {
            return jsonBody == null ? "" : jsonBody;
        }
    }

    /**
     * Thrown when web3forms does not acknowledge a submission as successful.
     */
    public static class SubmissionException extends Exception {
        public SubmissionException(String message) {
            super(message);
        }
    }
}