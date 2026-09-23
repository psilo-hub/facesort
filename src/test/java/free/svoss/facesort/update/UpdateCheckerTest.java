package free.svoss.facesort.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link UpdateChecker} version comparison logic and the
 * fetch-and-cache behaviour behind {@code checkForUpdate()}.
 */
class UpdateCheckerTest {

    @TempDir
    Path tempDir;

    private static final String CHANGELOG =
            "# Changelog\n\n"
            + "## [1.0-SNAPSHOT]\n\n"
            + "### Added\n"
            + "- Something new.\n\n"
            + "## [0.1.0]\n\n"
            + "### Added\n"
            + "- Initial release.\n";

    private static final byte[] NEWER_CHANGELOG =
            ("# Changelog\n\n## [9.9.9]\n\n### Added\n- Future.\n").getBytes(StandardCharsets.UTF_8);

    private static final byte[] OLDER_CHANGELOG =
            ("# Changelog\n\n## [0.0.1]\n\n### Added\n- Dawn of time.\n").getBytes(StandardCharsets.UTF_8);

    @Test
    void latestVersion_returnsNewestReleaseHeader() {
        assertEquals("1.0-SNAPSHOT", UpdateChecker.latestVersion(CHANGELOG));
    }

    @Test
    void latestVersion_ignoresLineEndings() {
        String crlf = CHANGELOG.replace("\n", "\r\n");
        assertEquals("1.0-SNAPSHOT", UpdateChecker.latestVersion(crlf));
    }

    @Test
    void latestVersion_returnsEmptyWhenNoReleaseHeader() {
        assertEquals("", UpdateChecker.latestVersion("# Changelog\n\nNo releases yet.\n"));
    }

    @Test
    void latestVersion_returnsEmptyForNull() {
        assertEquals("", UpdateChecker.latestVersion(null));
    }

    @Test
    void compareVersions_identicalVersionsAreEqual() {
        assertEquals(0, UpdateChecker.compareVersions("1.0.0", "1.0.0"));
    }

    @Test
    void compareVersions_sameVersionWithDifferentLineEndingsIsEqual() {
        String jarChangelog = CHANGELOG;
        String remoteChangelog = CHANGELOG.replace("\n", "\r\n");

        String localVersion = UpdateChecker.latestVersion(jarChangelog);
        String remoteVersion = UpdateChecker.latestVersion(remoteChangelog);

        assertEquals(0, UpdateChecker.compareVersions(remoteVersion, localVersion),
                "CRLF vs LF packaging must not be reported as a newer version");
    }

    @Test
    void compareVersions_newerRemoteIsGreater() {
        assertTrue(UpdateChecker.compareVersions("1.1.0", "1.0-SNAPSHOT") > 0);
    }

    @Test
    void compareVersions_olderRemoteIsSmaller() {
        assertTrue(UpdateChecker.compareVersions("0.1.0", "1.0-SNAPSHOT") < 0);
    }

    @Test
    void compareVersions_numericSegmentsComparedByValue() {
        assertTrue(UpdateChecker.compareVersions("1.10.0", "1.9.0") > 0);
    }

    @Test
    void compareVersions_extensionOfVersionIsNewer() {
        assertTrue(UpdateChecker.compareVersions("1.0.1", "1.0") > 0);
        assertTrue(UpdateChecker.compareVersions("1.0", "1.0.1") < 0);
    }

    @Test
    void compareVersions_prefixIsOlder() {
        assertTrue(UpdateChecker.compareVersions("2.0", "2.0.0-SNAPSHOT") < 0);
    }

    @Test
    void checkForUpdate_newerRemote_writesCacheAtomicallyAndTriggersNotice() throws Exception {
        AtomicBoolean notice = new AtomicBoolean(false);
        Path configDir = tempDir.resolve("cfg");
        UpdateChecker checker = checker(configDir, () -> NEWER_CHANGELOG, notice);

        checker.checkForUpdate();

        assertTrue(notice.get(), "a newer remote version must trigger the notice");
        Path cache = configDir.resolve("CHANGELOG.md");
        assertTrue(Files.exists(cache));
        assertEquals(new String(NEWER_CHANGELOG, StandardCharsets.UTF_8), Files.readString(cache),
                "the cache must hold exactly the fetched changelog");
        assertNoLeftoverTempFiles(configDir);
    }

    @Test
    void checkForUpdate_olderRemote_noNoticeButCacheStillRefreshed() throws Exception {
        AtomicBoolean notice = new AtomicBoolean(false);
        Path configDir = tempDir.resolve("cfg");
        UpdateChecker checker = checker(configDir, () -> OLDER_CHANGELOG, notice);

        checker.checkForUpdate();

        assertFalse(notice.get(), "an older remote version must not trigger the notice");
        Path cache = configDir.resolve("CHANGELOG.md");
        assertTrue(Files.exists(cache), "the cache must still be refreshed");
        assertNoLeftoverTempFiles(configDir);
    }

    @Test
    void checkForUpdate_fetchFailure_noNoticeAndNoCacheFile() throws Exception {
        AtomicBoolean notice = new AtomicBoolean(false);
        Path configDir = tempDir.resolve("cfg");
        UpdateChecker checker = checker(configDir, () -> {
            throw new IOException("network down");
        }, notice);

        checker.checkForUpdate();

        assertFalse(notice.get());
        assertFalse(Files.exists(configDir.resolve("CHANGELOG.md")));
    }

    @Test
    void checkForUpdate_nullOrEmptyResponse_noNoticeAndNoCacheFile() throws Exception {
        AtomicBoolean notice = new AtomicBoolean(false);
        Path configDir = tempDir.resolve("cfg");
        UpdateChecker checker = checker(configDir, () -> null, notice);

        checker.checkForUpdate();

        assertFalse(notice.get());
        assertFalse(Files.exists(configDir.resolve("CHANGELOG.md")));
    }

    @Test
    void checkForUpdate_freshCache_shortCircuitsBeforeFetching() throws Exception {
        Path configDir = tempDir.resolve("cfg");
        assertTrue(configDir.toFile().mkdirs());
        Files.writeString(configDir.resolve("CHANGELOG.md"),
                new String(NEWER_CHANGELOG, StandardCharsets.UTF_8));
        AtomicInteger fetches = new AtomicInteger();
        AtomicBoolean notice = new AtomicBoolean(false);
        UpdateChecker checker = checker(configDir, () -> {
            fetches.incrementAndGet();
            return NEWER_CHANGELOG;
        }, notice);

        checker.checkForUpdate();

        assertTrue(notice.get(), "a fresher cached version must trigger the notice");
        assertEquals(0, fetches.get(), "a fresh cache must skip the network fetch");
        assertNoLeftoverTempFiles(configDir);
    }

    @Test
    void checkForUpdate_staleOriginNoNotice_stillSkipsFetchWhenCacheIsFresh() throws Exception {
        Path configDir = tempDir.resolve("cfg");
        assertTrue(configDir.toFile().mkdirs());
        Files.writeString(configDir.resolve("CHANGELOG.md"),
                new String(OLDER_CHANGELOG, StandardCharsets.UTF_8));
        AtomicInteger fetches = new AtomicInteger();
        AtomicBoolean notice = new AtomicBoolean(false);
        UpdateChecker checker = checker(configDir, () -> {
            fetches.incrementAndGet();
            return NEWER_CHANGELOG;
        }, notice);

        checker.checkForUpdate();

        assertFalse(notice.get(), "a fresh but older cache must not trigger the notice");
        assertEquals(0, fetches.get(), "a fresh cache must skip the network fetch");
    }

    @Test
    void checkForUpdate_slowFetch_abortsOnTimeoutWithoutCacheOrNotice() throws Exception {
        AtomicBoolean notice = new AtomicBoolean(false);
        Path configDir = tempDir.resolve("cfg");
        UpdateChecker checker = new UpdateChecker(configDir,
                () -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return NEWER_CHANGELOG;
                },
                Duration.ofMillis(100), () -> notice.set(true));
        Instant start = Instant.now();

        checker.checkForUpdate();

        assertFalse(notice.get(), "a timed-out fetch must not trigger the notice");
        assertFalse(Files.exists(configDir.resolve("CHANGELOG.md")),
                "a timed-out fetch must not write a cache");
        assertTrue(Duration.between(start, Instant.now()).compareTo(Duration.ofSeconds(5)) < 0,
                "the check must give up shortly after the fetch timeout");
    }

    private static UpdateChecker checker(Path configDir, UpdateChecker.RemoteFetcher fetcher,
                                         AtomicBoolean notice) {
        return new UpdateChecker(configDir, fetcher, UpdateChecker.FETCH_TIMEOUT,
                () -> notice.set(true));
    }

    private static void assertNoLeftoverTempFiles(Path configDir) throws IOException {
        try (var files = Files.list(configDir)) {
            files.forEach(file ->
                    assertFalse(file.getFileName().toString().endsWith(".tmp"),
                            "no temp file may be left behind: " + file));
        }
    }
}
