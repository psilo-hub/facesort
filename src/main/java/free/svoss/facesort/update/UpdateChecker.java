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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Background update check.
 *
 * <p>On start, a dedicated daemon thread fetches the current {@code CHANGELOG.md}
 * from the project's GitHub repository and extracts the newest release version
 * from it. If that version is strictly newer than the one packaged inside the
 * running jar, a non-blocking notice dialog with a link to the latest release is
 * shown on the JavaFX application thread.</p>
 *
 * <p>Only the version number is compared, so packaging differences such as
 * line endings (CRLF vs LF) or whitespace never trigger a false notice.</p>
 *
 * <p>The check never blocks application startup, and every failure is written
 * to the console only.</p>
 */
public final class UpdateChecker {

    private static final Logger LOG = Logger.getLogger(UpdateChecker.class.getName());

    /** Hardcoded interval between update checks. */
    public static final Duration CHECK_INTERVAL = Duration.ofDays(2);

    private static final String CHANGELOG_RESOURCE = "/CHANGELOG.md";
    private static final String CHANGELOG_URL =
            "https://raw.githubusercontent.com/psilo-hub/facesort/refs/heads/main/src/main/resources/CHANGELOG.md";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?m)^##[ \\t]*\\[([^\\]]+)\\]");

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
            String localVersion = latestVersion(jarChangelog);
            Path configChangelog = configDir.resolve("CHANGELOG.md");

            if (Files.exists(configChangelog)) {
                Instant lastModified = Files.getLastModifiedTime(configChangelog).toInstant();
                if (!lastModified.isBefore(Instant.now().minus(CHECK_INTERVAL))) {
                    String cachedVersion = latestVersion(Files.readString(configChangelog));
                    if (compareVersions(cachedVersion, localVersion) > 0) {
                        showUpdateNotice();
                    }
                    return;
                }
            }

            byte[] downloaded = Fetcher.get(CHANGELOG_URL);
            if (downloaded == null || downloaded.length == 0) {
                LOG.log(Level.WARNING, "Update check: no data returned for {0}", CHANGELOG_URL);
                return;
            }

            Files.createDirectories(configDir);
            String remoteChangelog = new String(downloaded, StandardCharsets.UTF_8);
            Files.write(configChangelog, remoteChangelog.getBytes(StandardCharsets.UTF_8));

            String remoteVersion = latestVersion(remoteChangelog);
            if (compareVersions(remoteVersion, localVersion) > 0) {
                showUpdateNotice();
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Update check failed", e);
        }
    }

    /**
     * Extracts the newest release version from a changelog.
     *
     * @param changelog the changelog text, may be {@code null}
     * @return the version of the first {@code ## [version]} heading, or an empty
     *         string if the changelog contains no release heading
     */
    static String latestVersion(String changelog) {
        if (changelog == null) {
            return "";
        }
        Matcher matcher = VERSION_PATTERN.matcher(changelog);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /**
     * Compares two version strings segment by segment, treating numeric segments
     * by value and other segments case-insensitively. A version that is a prefix
     * of the other is considered older (for example {@code 1.0 < 1.0.1}).
     *
     * @param a first version
     * @param b second version
     * @return a negative value if {@code a} is older, zero if equal, a positive
     *         value if {@code a} is newer
     */
    static int compareVersions(String a, String b) {
        String[] left = a.split("[._\\-]");
        String[] right = b.split("[._\\-]");
        int segments = Math.max(left.length, right.length);
        for (int i = 0; i < segments; i++) {
            String leftSegment = i < left.length ? left[i] : "";
            String rightSegment = i < right.length ? right[i] : "";
            int result = compareSegments(leftSegment, rightSegment);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static int compareSegments(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            if (a.isEmpty() && b.isEmpty()) {
                return 0;
            }
            return a.isEmpty() ? -1 : 1;
        }
        if (isNumeric(a) && isNumeric(b)) {
            return compareNumeric(a, b);
        }
        return a.compareToIgnoreCase(b);
    }

    private static boolean isNumeric(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int compareNumeric(String a, String b) {
        String normalizedA = stripLeadingZeros(a);
        String normalizedB = stripLeadingZeros(b);
        if (normalizedA.length() != normalizedB.length()) {
            return Integer.compare(normalizedA.length(), normalizedB.length());
        }
        return normalizedA.compareTo(normalizedB);
    }

    private static String stripLeadingZeros(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
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
                LOG.log(Level.WARNING, "Failed to show the update notice", e);
            }
        });
    }
}