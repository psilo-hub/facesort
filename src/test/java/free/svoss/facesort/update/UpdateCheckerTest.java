package free.svoss.facesort.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link UpdateChecker} version comparison logic.
 */
class UpdateCheckerTest {

    private static final String CHANGELOG =
            "# Changelog\n\n"
            + "## [1.0-SNAPSHOT]\n\n"
            + "### Added\n"
            + "- Something new.\n\n"
            + "## [0.1.0]\n\n"
            + "### Added\n"
            + "- Initial release.\n";

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
}
