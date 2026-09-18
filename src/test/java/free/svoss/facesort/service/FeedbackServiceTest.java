package free.svoss.facesort.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FeedbackService} against a local mock HTTP server, so no
 * real submission ever reaches web3forms.com.
 */
class FeedbackServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void submit_success_sendsJsonPayloadWithCorrectFields() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = mockServer(200,
                "{\"success\":true,\"body\":{\"message\":\"Email sent successfully!\"}}",
                receivedBody, contentType);
        try {
            FeedbackService service = new FeedbackService(HttpClient.newHttpClient(), serverUrl(server));
            service.submit("Feature request", "Please add dark mode");

            assertEquals("application/json", contentType.get(),
                    "payload must be sent with the JSON content type");
            JsonNode sent = JSON.readTree(receivedBody.get());
            assertTrue(sent.path("access_key").asText().matches("[0-9a-f-]{36}"),
                    "payload must carry the access key");
            assertEquals("", sent.path("botcheck").asText(), "honeypot field must be empty");
            assertEquals("Feature request", sent.path("subject").asText(),
                    "payload must carry the type as subject");
            assertEquals("Please add dark mode", sent.path("message").asText(),
                    "payload must carry the message");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submit_rejectedHttpStatus_throwsSubmissionExceptionWithServerMessage() throws Exception {
        HttpServer server = mockServer(400,
                "{\"success\":false,\"message\":\"Invalid Access Key\"}",
                new AtomicReference<>(), new AtomicReference<>());
        try {
            FeedbackService service = new FeedbackService(HttpClient.newHttpClient(), serverUrl(server));
            FeedbackService.SubmissionException ex = assertThrows(
                    FeedbackService.SubmissionException.class,
                    () -> service.submit("Other", "This message is long enough"));
            assertTrue(ex.getMessage().contains("Invalid Access Key"),
                    "error must surface the server's message");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submit_successFalseInBody_throwsSubmissionException() throws Exception {
        HttpServer server = mockServer(200,
                "{\"success\":false,\"message\":\"Bot detected\"}",
                new AtomicReference<>(), new AtomicReference<>());
        try {
            FeedbackService service = new FeedbackService(HttpClient.newHttpClient(), serverUrl(server));
            FeedbackService.SubmissionException ex = assertThrows(
                    FeedbackService.SubmissionException.class,
                    () -> service.submit("Report an error", "A definitely long enough message"));
            assertTrue(ex.getMessage().contains("Bot detected"),
                    "error must surface the server's message");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submit_nonJsonResponse_throwsSubmissionException() throws Exception {
        HttpServer server = mockServer(200, "<html>please enable javascript</html>",
                new AtomicReference<>(), new AtomicReference<>());
        try {
            FeedbackService service = new FeedbackService(HttpClient.newHttpClient(), serverUrl(server));
            assertThrows(FeedbackService.SubmissionException.class,
                    () -> service.submit("Report an error", "A definitely long enough message"));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer mockServer(int status, String responseBody,
                                         AtomicReference<String> receivedBody,
                                         AtomicReference<String> contentType) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/submit", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private static String serverUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/submit";
    }
}