package free.svoss.facesort.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background update check.
 *
 * <p>On start, a dedicated daemon thread queries the GitHub Releases API for the
 * latest release of this repository and compares its tag (for example
 * {@code build-42}) with the build tag embedded into the running jar. If the
 * latest release is strictly newer, a non-blocking notice dialog with the
 * release version, the auto-generated release notes and a link to the release
 * page is shown on the JavaFX application thread.</p>
 *
 * <p>The local build tag is read from {@code /facesort-build.properties}; CI
 * builds embed their GitHub run number there ({@code build.number=build-&lt;n&gt;}).
 * Jars without a usable build tag (for example local builds) skip the check,
 * because there is nothing to compare the remote tag against.</p>
 *
 * <p>The check never blocks application startup: the fetch runs on a daemon
 * thread and is bounded by {@link #FETCH_TIMEOUT}, the fetched release
 * information is written to the cache atomically (a temp file plus an atomic
 * move, so a crash can never leave a truncated cache), and every failure is
 * written to the console only.</p>
 */
public final class UpdateChecker {

    private static final Logger LOG = Logger.getLogger(UpdateChecker.class.getName());

    /** Hardcoded interval between update checks. */
    public static final Duration CHECK_INTERVAL = Duration.ofDays(7);

    /** Upper bound for a single remote fetch, so a stalled network cannot hang the check. */
    public static final Duration FETCH_TIMEOUT = Duration.ofSeconds(20);

    private static final String BUILD_INFO_RESOURCE = "/facesort-build.properties";
    private static final String BUILD_NUMBER_KEY = "build.number";
    private static final String RELEASES_URL =
            "https://api.github.com/repos/psilo-hub/facesort/releases/latest";
    private static final String CACHE_FILE_NAME = "latest-release.json";

    private final Path configDir;
    private final String localVersion;
    private final RemoteFetcher fetcher;
    private final Duration fetchTimeout;
    private final Consumer<ReleaseInfo> updateAvailable;

    /**
     * Creates an update checker for the given config folder.
     *
     * @param configDir the folder the release cache is stored in
     */
    public UpdateChecker(Path configDir) {
        this(configDir, localBuildNumber(), UpdateChecker::fetchLatestRelease,
                FETCH_TIMEOUT, UpdateChecker::showUpdateNotice);
    }

    /**
     * Creates an update checker with configurable fetch and notice behaviour;
     * package-private for tests.
     *
     * @param configDir      the folder the release cache is stored in
     * @param localVersion   the build tag of the running jar; must not be null
     * @param fetcher        the remote fetch to use; must not be null
     * @param fetchTimeout   upper bound for the fetch; must not be null
     * @param updateAvailable invoked instead of the notice dialog when a newer
     *                        release is found; must not be null
     */
    UpdateChecker(Path configDir, String localVersion, RemoteFetcher fetcher, Duration fetchTimeout,
                  Consumer<ReleaseInfo> updateAvailable) {
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.localVersion = Objects.requireNonNull(localVersion, "localVersion");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.fetchTimeout = Objects.requireNonNull(fetchTimeout, "fetchTimeout");
        this.updateAvailable = Objects.requireNonNull(updateAvailable, "updateAvailable");
    }

    /**
     * Spawns a daemon thread that performs the update check.
     */
    public void startInBackground() {
        Thread thread = new Thread(this::checkForUpdate, "update-check");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Runs the update check once. If the running jar has no known build tag the
     * check is skipped. Otherwise the cache short-circuits the check when it was
     * refreshed within {@link #CHECK_INTERVAL}; else the latest release is
     * fetched within {@link #fetchTimeout}, written atomically to the cache, and
     * compared against the local build tag.
     *
     * <p>Package-private so tests can drive it directly.</p>
     */
    void checkForUpdate() {
        try {
            if (!isKnownBuild(localVersion)) {
                LOG.log(Level.FINE, "Update check: no embedded build number ({0}), skipping", localVersion);
                return;
            }
            Path cache = configDir.resolve(CACHE_FILE_NAME);

            if (Files.exists(cache)) {
                Instant lastModified = Files.getLastModifiedTime(cache).toInstant();
                if (!lastModified.isBefore(Instant.now().minus(CHECK_INTERVAL))) {
                    notifyIfNewer(parseReleaseInfo(Files.readString(cache)));
                    return;
                }
            }

            byte[] downloaded = fetchRemote();
            if (downloaded == null || downloaded.length == 0) {
                LOG.log(Level.WARNING, "Update check: no data returned for {0}", RELEASES_URL);
                return;
            }

            Files.createDirectories(configDir);
            writeCacheAtomically(cache, downloaded);

            notifyIfNewer(parseReleaseInfo(new String(downloaded, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Update check failed", e);
        }
    }

    private void notifyIfNewer(ReleaseInfo release) {
        if (release != null && compareVersions(release.tagName(), localVersion) > 0) {
            updateAvailable.accept(release);
        }
    }

    /**
     * Fetches the latest release, bounded by {@link #fetchTimeout}. The fetch
     * itself runs on a daemon executor thread so that a network that never
     * answers cannot block the application or the JVM exit; if it times out, the
     * check is abandoned (the cache is simply not refreshed).
     */
    private byte[] fetchRemote() throws IOException {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "update-check-fetch");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<byte[]> future = executor.submit(fetcher::fetch);
            try {
                return future.get(fetchTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new IOException("Timed out fetching " + RELEASES_URL + " after " + fetchTimeout, e);
            } catch (ExecutionException e) {
                throw new IOException("Fetching " + RELEASES_URL + " failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while fetching " + RELEASES_URL, e);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Fetches the GitHub Releases API's latest-release endpoint. The GitHub API
     * requires a {@code User-Agent} header, so the fetch goes out over a plain
     * HTTPS connection instead of the (removed) raw-file fetcher.
     */
    private static byte[] fetchLatestRelease() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout((int) FETCH_TIMEOUT.toMillis());
            connection.setReadTimeout((int) FETCH_TIMEOUT.toMillis());
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "FaceSort");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status + " fetching " + RELEASES_URL);
            }
            try (InputStream in = connection.getInputStream()) {
                return in.readAllBytes();
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Writes the downloaded release JSON to the cache file through a temp file
     * and an atomic move, so an interrupted write can never leave a truncated
     * cache behind. Falls back to a non-atomic move where the filesystem does
     * not support atomic moves.
     */
    private static void writeCacheAtomically(Path target, byte[] content) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), "release", ".tmp");
        try {
            Files.write(tmp, content);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Extracts the release data from the GitHub API's latest-release response.
     *
     * @param response the JSON response body, may be {@code null}
     * @return the parsed release, or {@code null} when the response is missing,
     *         not valid JSON, or carries no {@code tag_name}
     */
    static ReleaseInfo parseReleaseInfo(String response) {
        if (response == null) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(response);
            String tag = root.path("tag_name").asText("").trim();
            if (tag.isEmpty()) {
                return null;
            }
            return new ReleaseInfo(tag,
                    root.path("html_url").asText("").trim(),
                    root.path("body").asText("").trim());
        } catch (IOException e) {
            LOG.log(Level.FINE, "Update check: could not parse the release response", e);
            return null;
        }
    }

    /**
     * Reads the build tag embedded into the jar by the CI build.
     *
     * @return the {@code build.number} from {@code /facesort-build.properties},
     *         or an empty string when the resource is absent or unreadable
     */
    static String localBuildNumber() {
        try (InputStream in = UpdateChecker.class.getResourceAsStream(BUILD_INFO_RESOURCE)) {
            if (in == null) {
                return "";
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty(BUILD_NUMBER_KEY, "").trim();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Update check: could not read the embedded build number", e);
            return "";
        }
    }

    /**
     * Whether a build tag is usable for the comparison. {@code "0"} is the
     * placeholder embedded by default (local/dev builds); such jars skip the
     * update check because they have no release to be newer than.
     *
     * @param buildNumber the build tag to check
     * @return {@code true} when the tag can be compared against a release tag
     */
    static boolean isKnownBuild(String buildNumber) {
        return buildNumber != null && !buildNumber.isBlank() && !"0".equals(buildNumber);
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

    private static void showUpdateNotice(ReleaseInfo release) {
        Platform.runLater(() -> {
            try {
                new UpdateNoticeDialog(release).show();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to show the update notice", e);
            }
        });
    }

    /**
     * The data of the latest release that the update check shows.
     *
     * @param tagName the release tag, e.g. {@code build-42}; never blank
     * @param htmlUrl the release page URL; may be empty when the response omitted it
     * @param notes   the auto-generated release notes; may be empty
     */
    record ReleaseInfo(String tagName, String htmlUrl, String notes) {
    }

    /**
     * Fetches the latest release JSON as raw bytes. Package-private seam so tests
     * can drive {@link #checkForUpdate()} without network access.
     */
    @FunctionalInterface
    interface RemoteFetcher {

        byte[] fetch() throws IOException;
    }
}