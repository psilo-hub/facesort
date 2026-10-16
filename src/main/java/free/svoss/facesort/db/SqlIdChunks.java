package free.svoss.facesort.db;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Splits a list of SQL bind values into chunks small enough to stay below
 * SQLite's variable-number limit, and builds the matching {@code (?...)} bind
 * list.
 *
 * <p>SQLite caps the number of host parameters a single statement may carry
 * (999 in older builds, higher in newer ones), so a {@code WHERE ... IN (?...)}
 * lookup cannot bind an arbitrarily long key list. Every batched DAO lookup
 * funnels through this class, so that cap — and the safe batch size chosen
 * below it — is defined exactly once and cannot drift between the DAOs.</p>
 */
final class SqlIdChunks {

    /**
     * Maximum number of values bound per {@code WHERE ... IN (...)} lookup,
     * kept well below SQLite's variable-number limit (999 in older builds) so a
     * large key list cannot exhaust it.
     */
    static final int MAX_IN_IDS = 500;

    private SqlIdChunks() {
    }

    /**
     * Splits {@code values} into consecutive chunks of at most
     * {@link #MAX_IN_IDS}, in order. The chunks together hold exactly the input
     * values, in the input order; an empty input yields no chunks, so a caller
     * that loops over them issues no statement for an empty lookup.
     *
     * @param values the values to split; must not be {@code null}
     * @param <T>    the value type
     * @return the chunks, each at most {@link #MAX_IN_IDS} long
     */
    static <T> List<List<T>> partition(List<T> values) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < values.size(); from += MAX_IN_IDS) {
            chunks.add(new ArrayList<>(values.subList(from, Math.min(values.size(), from + MAX_IN_IDS))));
        }
        return chunks;
    }

    /**
     * Returns a comma-separated list of {@code count} bind placeholders, e.g.
     * {@code "?,?,?"} for three.
     *
     * @param count the number of placeholders
     * @return the placeholder list, empty when {@code count} is zero
     */
    static String placeholders(int count) {
        return IntStream.range(0, count).mapToObj(i -> "?").collect(Collectors.joining(","));
    }
}
