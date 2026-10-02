package free.svoss.facesort.db;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PathPrefix}: the trimmed prefix, the lexicographic upper
 * bound and the SQL fragment both DAOs build their path filters from. No
 * database involved — the equivalence with the plain string-prefix semantics is
 * checked against the comparison SQLite's BINARY collation performs.
 */
class PathPrefixTest {

    private static final String EMOJI = "\uD83C\uDF05";         // U+1F305, needs a surrogate pair
    private static final String NEXT_EMOJI = "\uD83C\uDF06";    // U+1F306, its successor
    private static final String MAX_CODE_POINT = "\uDBFF\uDFFF";   // U+10FFFF
    private static final String REPLACEMENT = "\uFFFD";        // U+FFFD, the largest non-surrogate BMP code point

    @Test
    void nullOrBlankPrefix_isDisabled() {
        assertTrue(PathPrefix.of(null).isDisabled());
        assertTrue(PathPrefix.of("").isDisabled());
        assertTrue(PathPrefix.of("   ").isDisabled());
        assertTrue(PathPrefix.of("\t\n ").isDisabled());
    }

    @Test
    void disabledPrefix_matchesEveryPathAndHasNoUpperBound() {
        PathPrefix disabled = PathPrefix.of("  ");

        assertEquals("", disabled.value());
        assertNull(disabled.upperBound());
        assertEquals("path >= ?", disabled.condition("path"),
                "a disabled filter has no upper bound, so its range cannot exclude anything");
    }

    @Test
    void prefix_isTrimmed() {
        PathPrefix filter = PathPrefix.of("  /photos/family  ");

        assertFalse(filter.isDisabled());
        assertEquals("/photos/family", filter.value());
    }

    @Test
    void upperBound_incrementsTheLastCodePoint() {
        assertEquals("C:\\photot", PathPrefix.of("C:\\photos").upperBound());
        assertEquals("/photos/famim", PathPrefix.of("/photos/famil").upperBound());
    }

    @Test
    void upperBound_incrementsAcrossTheBmpBoundary() {
        assertEquals("/photos/{", PathPrefix.of("/photos/z").upperBound());
        assertEquals("\uFFFE", PathPrefix.of(REPLACEMENT).upperBound(),
                "the largest non-surrogate BMP code point is still incremented as a code point");
    }

    @Test
    void upperBound_carriesOverATrailingMaxCodePoint() {
        assertEquals("b", PathPrefix.of("a" + MAX_CODE_POINT).upperBound());
        assertEquals("x" + MAX_CODE_POINT + "z",
                PathPrefix.of("x" + MAX_CODE_POINT + "y" + MAX_CODE_POINT).upperBound());
    }

    @Test
    void upperBound_isAbsentWhenEveryCodePointIsMax() {
        assertNull(PathPrefix.of(MAX_CODE_POINT).upperBound());
        assertNull(PathPrefix.of(MAX_CODE_POINT + MAX_CODE_POINT).upperBound());
    }

    @Test
    void condition_isALexicographicRangeOnTheGivenColumn() {
        assertEquals("p.path >= ? AND p.path < ?", PathPrefix.of("/photos").condition("p.path"));
        assertEquals("path >= ?", PathPrefix.of(MAX_CODE_POINT).condition("path"));
    }

    @Test
    void rangeSelectsExactlyThePathsStartingWithThePrefix() {
        assertRangeSelects("/photos", "/photos/a.jpg", "/photos", "/photos/sub/deep.jpg",
                "/photos-archive/x.jpg");
        assertRangeSelects("C:\\photos", "C:\\photos\\a.jpg", "C:\\photos2\\b.jpg", "C:\\photos");
        assertRangeSelects("/photos/" + EMOJI, "/photos/" + EMOJI + "/a.jpg", "/photos/" + EMOJI + "-x");
        assertRangeSelects("/a" + REPLACEMENT, "/a" + REPLACEMENT + "/x.jpg");
        assertRangeSelects("/v", "/v/x.jpg", "/v2/x.jpg");
        assertRangeSelects("x" + MAX_CODE_POINT + "y" + MAX_CODE_POINT,
                "x" + MAX_CODE_POINT + "y" + MAX_CODE_POINT,
                "x" + MAX_CODE_POINT + "y" + MAX_CODE_POINT + "/a.jpg");

        assertRangeExcludes("/photos", "/Photos/a.jpg", "/photo/a.jpg", "x/photos/a.jpg", "photos/a.jpg");
        assertRangeExcludes("C:\\photos", "C:\\Photos\\a.jpg", "D:\\photos", "C:/photos");
        assertRangeExcludes("/photos/" + EMOJI, "/photos/" + NEXT_EMOJI + "/b.jpg", "/photos/a.jpg");
        assertRangeExcludes("/a" + REPLACEMENT, "/a" + EMOJI + "/x.jpg", "/ab");
        assertRangeExcludes("x" + MAX_CODE_POINT + "y" + MAX_CODE_POINT, "x" + MAX_CODE_POINT + "z", "a");
    }

    @Test
    void prefixOfOnlyMaxCodePoints_hasNoUpperBound() {
        PathPrefix filter = PathPrefix.of(MAX_CODE_POINT);

        assertNull(filter.upperBound());
        assertTrue(inRange(filter, MAX_CODE_POINT));
        assertTrue(inRange(filter, MAX_CODE_POINT + "/a.jpg"));
        assertFalse(inRange(filter, "a"), "nothing sorts above the largest code point without starting with it");
    }

    /**
     * Asserts that the range {@link PathPrefix} computes selects the given
     * paths — including the near-miss siblings that merely share a longer
     * common beginning — and nothing else.
     */
    private static void assertRangeSelects(String prefix, String... selected) {
        PathPrefix filter = PathPrefix.of(prefix);
        for (String path : selected) {
            assertTrue(path.startsWith(prefix), "test table: " + path + " must start with " + prefix);
            assertTrue(inRange(filter, path), () -> path + " must be selected by the range for " + prefix);
        }
    }

    private static void assertRangeExcludes(String prefix, String... rejected) {
        PathPrefix filter = PathPrefix.of(prefix);
        for (String path : rejected) {
            assertFalse(path.startsWith(prefix), "test table: " + path + " must not start with " + prefix);
            assertFalse(inRange(filter, path), () -> path + " must not be selected by the range for " + prefix);
        }
    }

    private static boolean inRange(PathPrefix filter, String path) {
        return compareUtf8(path, filter.value()) >= 0
                && (filter.upperBound() == null || compareUtf8(path, filter.upperBound()) < 0);
    }

    /**
     * Compares two strings the way SQLite's BINARY collation does — unsigned
     * byte by byte over their UTF-8 encoding — rather than by UTF-16 code unit,
     * which orders supplementary code points before U+E000..U+FFFF and would
     * therefore misjudge paths containing emoji.
     */
    private static int compareUtf8(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int shared = Math.min(leftBytes.length, rightBytes.length);
        for (int i = 0; i < shared; i++) {
            int difference = (leftBytes[i] & 0xFF) - (rightBytes[i] & 0xFF);
            if (difference != 0) {
                return difference;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    @Test
    void everyStoredPathThatStartsWithThePrefixIsBelowTheUpperBound() {
        List<String> paths = List.of("", "a", "a/b", "/photos", "/photos/a.jpg", "/photos-archive",
                "/photoss", "/phott", "/photo\u0000", "/Photos/a.jpg");

        for (String prefix : List.of("", "/", "/photos", "/photos/", "/photos/a", "/phot", "/photo\u0000")) {
            PathPrefix filter = PathPrefix.of(prefix);
            for (String path : paths) {
                if (path.startsWith(prefix)) {
                    assertTrue(inRange(filter, path),
                            () -> "path " + path + " starts with " + prefix + " but falls outside its range");
                }
            }
        }
    }
}
