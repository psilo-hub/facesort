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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link UpdateChecker} version comparison, release-JSON parsing and
 * the fetch-and-cache behaviour behind {@code checkForUpdate()}.
 */
class UpdateCheckerTest {

    @TempDir
    Path tempDir;

    private static final String RELEASE =
            """
            {
              "tag_name": "build-3",
              "html_url": "https://github.com/psilo-hub/facesort/releases/tag/build-3",
              "body": "## What's Changed\\n* Something."
            }
            """;

    private static final byte[] NEWER_RELEASE = releaseBytes("build-9", "Future.");
    private static final byte[] OLDER_RELEASE = releaseBytes("build-1", "Dawn of time.");

    private static byte[] releaseBytes(String tag, String notes) {
        return ("{"
                + "\"tag_name\":\"" + tag + "\","
                + "\"html_url\":\"https://github.com/psilo-hub/facesort/releases/tag/" + tag + "\","
                + "\"body\":\"" + notes + "\""
                + "}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parseReleaseInfo_returnsTagUrlAndNotes() {
        UpdateChecker.ReleaseInfo release = UpdateChecker.parseReleaseInfo(RELEASE);

        assertNotNull(release);
        assertEquals("build-3", release.tagName());
        assertEquals("https://github.com/psilo-hub/facesort/releases/tag/build-3", release.htmlUrl());
        assertEquals("## What's Changed\n* Something.", release.notes());
    }

    @Test
    void parseReleaseInfo_toleratesMissingOptionalFields() {
        UpdateChecker.ReleaseInfo release = UpdateChecker.parseReleaseInfo(
                "{\"tag_name\":\"build-1\"}");

        assertNotNull(release);
        assertEquals("build-1", release.tagName());
        assertEquals("", release.htmlUrl());
        assertEquals("", release.notes());
    }

    @Test
    void parseReleaseInfo_returnsNullWithoutTag() {
        assertNull(UpdateChecker.parseReleaseInfo("{\"html_url\":\"https://example.test\"}"));
    }

    @Test
    void parseReleaseInfo_returnsNullForNonJson() {
        assertNull(UpdateChecker.parseReleaseInfo("not json"));
    }

    @Test
    void parseReleaseInfo_returnsNullForNull() {
        assertNull(UpdateChecker.parseReleaseInfo(null));
    }

    @Test
    void isKnownBuild_acceptsCiBuildTags() {
        assertTrue(UpdateChecker.isKnownBuild("build-123"));
        assertTrue(UpdateChecker.isKnownBuild("v1.2.3"));
    }

    @Test
    void isKnownBuild_rejectsPlaceholdersAndBlanks() {
        assertFalse(UpdateChecker.isKnownBuild("0"));
        assertFalse(UpdateChecker.isKnownBuild(""));
        assertFalse(UpdateChecker.isKnownBuild("   "));
        assertFalse(UpdateChecker.isKnownBuild(null));
    }

    @Test
    void compareVersions_buildTagsComparedByRunNumber() {
        assertTrue(UpdateChecker.compareVersions("build-123", "build-42") > 0);
        assertEquals(0, UpdateChecker.compareVersions("build-42", "build-42"));
        assertTrue(UpdateChecker.compareVersions("build-9", "build-10") < 0);
    }

    @Test
    void compareVersions_identicalVersionsAreEqual() {
        assertEquals(0, UpdateChecker.compareVersions("1.0.0", "1.0.0"));
    }

    @Test
    void compareVersions_newerRemoteIsGreater() {
        assertTrue(UpdateChecker.compareVersions("build-2", "build-1") > 0);
    }

    @Test
    void compareVersions_olderRemoteIsSmaller() {
        assertTrue(UpdateChecker.compareVersions("build-1", "build-2") < 0);
    }

    @Test
    void compareVersions_numericSegmentsComparedByValue() {
        assertTrue(UpdateChecker.compareVersions("build-110", "build-99") > 0);
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
        UpdateChecker checker = checker(configDir, "build-1", () -> NEWER_RELEASE, notice);

        checker.checkForUpdate();

        assertTrue(notice.get(), "a newer remote release must trigger the notice");
        Path cache = configDir.resolve("latest-release.json");
        assertTrue(Files.exists(cache));
        assertEquals(new String(NEWER_RELEASE, StandardCharsets.UTF_8), Files.readString(cache),
                "the cache must hold exactly the fetched release JSON");
        assertNoLeftoverTempFiles(configDir);
    }

    @Test
    void checkForUpdate_olderRemote_noNoticeButCacheStillRefreshed() throws Exception {
        AtomicBoolean notice = new AtomicBoolean(false);
        Path configDir = tempDir.resolve("cfg");
        UpdateChecker checker = checker(configDir, "build-5", () -> OLDER_RELEASE, notice);

        checker.checkForUpdate();

        assertFalse(notice.get(), "an older remote release must not trigger the notice");
        Path cache = configDir.resolve("latest-release.json");
        assertTrue(Files.exists(cache), "the cache must still be refreshed");
        assertNoLeftoverTempFiles(configDir);
    }

    @Test
    void checkForUpdate_sameVersion_noNoticeButCacheStillRefreshed() throws Exception {
        AtomicBoolean notice = new AtomicBoolean(false);
        Path configDir = tempDir.resolve("cfg");
        UpdateChecker checker = checker(configDir, "build-9", () -> NEWER_RELEASE, notice);

        checker.checkForUpdate();

        assertFalse(notice.get(), "the latest released build must not be reported as newer");
        assertTrue(Files.exists(configDir.resolve("latest-release.json")),
                "the cache must still be refreshed");
    }

    @Test
    void checkForUpdate_fetchFailure_noNoticeAndNoCacheFile() throws Exception {
        AtomicBoolean notice = new AtomicBoolean(false);
        Path configDir = tempDir.resolve("cfg");
        UpdateChecker checker = checker(configDir, "build-1", () -> {
            throw new IOException("network down");
        }, notice);

        checker.checkForUpdate();

        assertFalse(notice.get());
        assertFalse(Files.exists(configDir.resolve("latest-release.json")));
    }

    @Test
    void checkForUpdate_nullOrEmptyResponse_noNoticeAndNoCacheFile() throws Exception {
        AtomicBoolean notice = new AtomicBoolean(false);
        Path configDir = tempDir.resolve("cfg");
        UpdateChecker checker = checker(configDir, "build-1", () -> null, notice);

        checker.checkForUpdate();

        assertFalse(notice.get());
        assertFalse(Files.exists(configDir.resolve("latest-release.json")));
    }

    @Test
    void checkForUpdate_invalidJson_noNoticeButCacheStillRefreshed() throws Exception {
        AtomicBoolean notice = new AtomicBoolean(false);
        Path configDir = tempDir.resolve("cfg");
        UpdateChecker checker = checker(configDir, "build-1",
                () -> "not json".getBytes(StandardCharsets.UTF_8), notice);

        checker.checkForUpdate();

        assertFalse(notice.get());
        assertTrue(Files.exists(configDir.resolve("latest-release.json")),
                "the cache must still be refreshed so the malformed response is not refetched");
        assertNoLeftoverTempFiles(configDir);
    }

    @Test
    void checkForUpdate_freshCache_shortCircuitsBeforeFetching() throws Exception {
        Path configDir = tempDir.resolve("cfg");
        assertTrue(configDir.toFile().mkdirs());
        Files.writeString(configDir.resolve("latest-release.json"),
                new String(NEWER_RELEASE, StandardCharsets.UTF_8));
        AtomicInteger fetches = new AtomicInteger();
        AtomicBoolean notice = new AtomicBoolean(false);
        UpdateChecker checker = checker(configDir, "build-1", () -> {
            fetches.incrementAndGet();
            return NEWER_RELEASE;
        }, notice);

        checker.checkForUpdate();

        assertTrue(notice.get(), "a fresher cached release must trigger the notice");
        assertEquals(0, fetches.get(), "a fresh cache must skip the network fetch");
        assertNoLeftoverTempFiles(configDir);
    }

    @Test
    void checkForUpdate_staleOriginNoNotice_stillSkipsFetchWhenCacheIsFresh() throws Exception {
        Path configDir = tempDir.resolve("cfg");
        assertTrue(configDir.toFile().mkdirs());
        Files.writeString(configDir.resolve("latest-release.json"),
                new String(OLDER_RELEASE, StandardCharsets.UTF_8));
        AtomicInteger fetches = new AtomicInteger();
        AtomicBoolean notice = new AtomicBoolean(false);
        UpdateChecker checker = checker(configDir, "build-5", () -> {
            fetches.incrementAndGet();
            return NEWER_RELEASE;
        }, notice);

        checker.checkForUpdate();

        assertFalse(notice.get(), "a fresh but older cache must not trigger the notice");
        assertEquals(0, fetches.get(), "a fresh cache must skip the network fetch");
    }

    @Test
    void checkForUpdate_unknownBuildNumber_skipsWithoutFetching() throws Exception {
        Path configDir = tempDir.resolve("cfg");
        AtomicInteger fetches = new AtomicInteger();
        AtomicBoolean notice = new AtomicBoolean(false);
        UpdateChecker checker = checker(configDir, "0", () -> {
            fetches.incrementAndGet();
            return NEWER_RELEASE;
        }, notice);

        checker.checkForUpdate();

        assertFalse(notice.get(), "a jar without a build number must not trigger the notice");
        assertEquals(0, fetches.get(), "a jar without a build number must not fetch");
        assertFalse(Files.exists(configDir.resolve("latest-release.json")));
    }

    @Test
    void checkForUpdate_slowFetch_abortsOnTimeoutWithoutCacheOrNotice() throws Exception {
        AtomicBoolean notice = new AtomicBoolean(false);
        Path configDir = tempDir.resolve("cfg");
        UpdateChecker checker = new UpdateChecker(configDir, "build-1",
                () -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return NEWER_RELEASE;
                },
                Duration.ofMillis(100), release -> notice.set(true));
        Instant start = Instant.now();

        checker.checkForUpdate();

        assertFalse(notice.get(), "a timed-out fetch must not trigger the notice");
        assertFalse(Files.exists(configDir.resolve("latest-release.json")),
                "a timed-out fetch must not write a cache");
        assertTrue(Duration.between(start, Instant.now()).compareTo(Duration.ofSeconds(5)) < 0,
                "the check must give up shortly after the fetch timeout");
    }

    private static UpdateChecker checker(Path configDir, String localVersion,
                                         UpdateChecker.RemoteFetcher fetcher, AtomicBoolean notice) {
        return new UpdateChecker(configDir, localVersion, fetcher, UpdateChecker.FETCH_TIMEOUT,
                release -> notice.set(true));
    }

    private static void assertNoLeftoverTempFiles(Path configDir) throws IOException {
        try (var files = Files.list(configDir)) {
            files.forEach(file ->
                    assertFalse(file.getFileName().toString().endsWith(".tmp"),
                            "no temp file may be left behind: " + file));
        }
    }
}