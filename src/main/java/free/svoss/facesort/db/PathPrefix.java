package free.svoss.facesort.db;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * A stored-file-path prefix filter, expressed as the lexicographic range
 * {@code column >= prefix AND column < successor(prefix)} so SQLite can serve it
 * from an index on the path column instead of evaluating {@code substr()} on
 * every row.
 *
 * <p>The range selects exactly the paths that start with the prefix — the
 * semantics the filter has always had, byte-wise and case-sensitive, so
 * {@code /photos/family} still matches {@code /photos/family-archive/a.jpg}:
 * a plain string prefix, not a folder boundary. Callers that read a blank
 * prefix as "no filter at all" check {@link #isDisabled()} and omit the
 * condition rather than using {@link #condition(String)}.</p>
 *
 * <p>The upper bound is the prefix with its last code point incremented, so it
 * is a valid string that BINARY (memcmp) collation places immediately after
 * everything the prefix starts. The increment is done over code points rather
 * than over UTF-16 units: SQLite compares the UTF-8 encoding, and UTF-8 is
 * order-preserving, so a code-point increment yields a bound that orders
 * correctly, while incrementing a surrogate pair's halves or an unpaired
 * surrogate would produce an invalid sequence (a lone {@code \uDC00} after
 * {@code \uDBFF\uDFFF}, for instance) that the JDBC driver silently substitutes
 * on the way into SQLite — a bound that is too small then under-counts, and
 * {@link DataRemovalDao} would under-delete. Trailing U+10FFFF code points are
 * dropped and the increment carries over to the code point before them; a
 * prefix consisting only of U+10FFFF has no upper bound at all, because nothing
 * sorts above it without starting with it.</p>
 */
final class PathPrefix {

    private static final PathPrefix DISABLED = new PathPrefix("", null);

    private final String prefix;
    private final String upperBound;

    private PathPrefix(String prefix, String upperBound) {
        this.prefix = prefix;
        this.upperBound = upperBound;
    }

    /**
     * Creates the filter for a user-entered prefix. The prefix is trimmed, so
     * surrounding whitespace cannot silently narrow the match; a {@code null} or
     * blank prefix yields a {@linkplain #isDisabled() disabled} filter.
     *
     * @param rawPrefix the prefix as entered; may be {@code null} or blank
     * @return the filter for that prefix
     */
    static PathPrefix of(String rawPrefix) {
        String trimmed = rawPrefix == null ? "" : rawPrefix.trim();
        return trimmed.isEmpty() ? DISABLED : new PathPrefix(trimmed, successorOf(trimmed));
    }

    /**
     * @return {@code true} when the prefix was {@code null} or blank, i.e. when
     *         the caller should not filter at all
     */
    boolean isDisabled() {
        return prefix.isEmpty();
    }

    /**
     * @return the trimmed prefix the range starts at
     */
    String value() {
        return prefix;
    }

    /**
     * @return the exclusive upper bound of the range, or {@code null} when the
     *         prefix has none (a disabled filter, or a prefix made only of
     *         U+10FFFF)
     */
    String upperBound() {
        return upperBound;
    }

    /**
     * Returns the SQL condition restricting the given column to the paths
     * starting with the prefix, with one {@code ?} per bound value — one when
     * there is no upper bound, two otherwise. The column name is interpolated,
     * so it must be a constant of the calling DAO, never user input.
     *
     * @param column the path column to filter
     * @return the condition to append to a {@code WHERE} clause
     */
    String condition(String column) {
        return upperBound == null
                ? column + " >= ?"
                : column + " >= ? AND " + column + " < ?";
    }

    /**
     * Binds the values of one {@link #condition(String)}, starting at
     * {@code firstIndex}, and returns the index just after the last one bound.
     * Call it once per condition the statement contains, in the order the
     * conditions appear, so the fragment and the bound values always describe
     * the same placeholders.
     *
     * @param ps         the prepared statement to bind
     * @param firstIndex the index of the condition's first {@code ?}
     * @return the first index after the last one bound
     * @throws SQLException on database error
     */
    int bind(PreparedStatement ps, int firstIndex) throws SQLException {
        ps.setString(firstIndex, prefix);
        if (upperBound == null) {
            return firstIndex + 1;
        }
        ps.setString(firstIndex + 1, upperBound);
        return firstIndex + 2;
    }

    /**
     * Returns the smallest string that sorts after everything starting with the
     * given prefix, or {@code null} when no such string exists.
     */
    private static String successorOf(String prefix) {
        int end = prefix.length();
        while (end > 0) {
            int start = prefix.offsetByCodePoints(end, -1);
            int lastCodePoint = prefix.codePointBefore(end);
            if (lastCodePoint < Character.MAX_CODE_POINT) {
                return prefix.substring(0, start)
                        + new String(Character.toChars(lastCodePoint + 1));
            }
            end = start;
        }
        return null;
    }
}
