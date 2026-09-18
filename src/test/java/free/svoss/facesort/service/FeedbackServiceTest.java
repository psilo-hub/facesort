package free.svoss.facesort.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FeedbackService} against a local mock HTTP server, so no
 * real submission ever reaches web3forms.com.
 */
class FeedbackServiceTest {

    @Test
    void submit_success_sendsEncodedFormAndReturns() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        HttpServer server = mockServer(200, "{\"success\":true}", receivedBody);
        try {
            FeedbackService service = new FeedbackService(HttpClient.newHttpClient(), serverUrl(server));
            service.submit("Feature request", "Please add dark mode");

            String body = receivedBody.get();
            assertTrue(body.contains("access_key="), "form must carry the access key");
            assertTrue(body.contains("botcheck="), "form must carry the empty honeypot field");
            assertTrue(body.contains("subject=Feature+request"), "form must carry the type as subject");
            assertTrue(body.contains("message=Please+add+dark+mode"),
                    "form must carry the URL-encoded message");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submit_rejectedHttpStatus_throwsSubmissionException() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        HttpServer server = mockServer(400, "{\"success\":false}", receivedBody);
        try {
            FeedbackService service = new FeedbackService(HttpClient.newHttpClient(), serverUrl(server));
            assertThrows(FeedbackService.SubmissionException.class,
                    () -> service.submit("Other", "This message is long enough"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submit_successFalseInBody_throwsSubmissionException() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        HttpServer server = mockServer(200, "{\"success\":false}", receivedBody);
        try {
            FeedbackService service = new FeedbackService(HttpClient.newHttpClient(), serverUrl(server));
            assertThrows(FeedbackService.SubmissionException.class,
                    () -> service.submit("Report an error", "A definitely long enough message"));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer mockServer(int status, String responseBody,
                                         AtomicReference<String> receivedBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/submit", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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