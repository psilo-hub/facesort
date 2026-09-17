package free.svoss.facesort.update;

import free.svoss.tools.rawGitHubFetcher.Fetcher;
import javafx.application.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Background update check.
 *
 * <p>On start, a dedicated daemon thread fetches the current {@code CHANGELOG.md}
 * from the project's GitHub repository and compares it with the copy packaged
 * inside the running jar. If they differ, a non-blocking notice dialog with a
 * link to the latest release is shown on the JavaFX application thread.</p>
 *
 * <p>The check never blocks application startup, and every failure is written
 * to the console only.</p>
 */
public final class UpdateChecker {

    /** Hardcoded interval between update checks. */
    public static final Duration CHECK_INTERVAL = Duration.ofDays(2);

    private static final String CHANGELOG_RESOURCE = "/CHANGELOG.md";
    private static final String CHANGELOG_URL =
            "https://raw.githubusercontent.com/psilo-hub/facesort/main/CHANGELOG.md";

    private final Path configDir;

    /**
     * Creates an update checker for the given config folder.
     *
     * @param configDir the folder the local changelog is stored in
     */
    public UpdateChecker(Path configDir) {
        this.configDir = Objects.requireNonNull(configDir, "configDir");
    }

    /**
     * Spawns a daemon thread that performs the update check.
     */
    public void startInBackground() {
        Thread thread = new Thread(this::checkForUpdate, "update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private void checkForUpdate() {
        try {
            String jarChangelog = readJarChangelog();
            Path configChangelog = configDir.resolve("CHANGELOG.md");

            if (Files.exists(configChangelog)) {
                Instant lastModified = Files.getLastModifiedTime(configChangelog).toInstant();
                if (!lastModified.isBefore(Instant.now().minus(CHECK_INTERVAL))) {
                    if (!jarChangelog.equals(Files.readString(configChangelog))) {
                        showUpdateNotice();
                    }
                    return;
                }
            }

            byte[] downloaded = Fetcher.get(CHANGELOG_URL);
            if (downloaded == null || downloaded.length == 0) {
                System.err.println("Update check: no data returned for " + CHANGELOG_URL);
                return;
            }

            Files.createDirectories(configDir);
            String remoteChangelog = new String(downloaded, StandardCharsets.UTF_8);
            Files.write(configChangelog, remoteChangelog.getBytes(StandardCharsets.UTF_8));

            if (!jarChangelog.equals(remoteChangelog)) {
                showUpdateNotice();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String readJarChangelog() throws IOException {
        try (InputStream in = UpdateChecker.class.getResourceAsStream(CHANGELOG_RESOURCE)) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void showUpdateNotice() {
        Platform.runLater(() -> {
            try {
                new UpdateNoticeDialog().show();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}