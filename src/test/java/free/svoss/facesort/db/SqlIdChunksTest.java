package free.svoss.facesort.db;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SqlIdChunks}: the single source of truth for the
 * {@code WHERE ... IN (?...)} bind limit both DAOs batch their lookups through.
 * No database involved — the chunk boundaries and the placeholder list are
 * checked directly.
 */
class SqlIdChunksTest {

    @Test
    void partition_keepsAShortListInOneChunk() {
        List<Integer> keys = numbers(500);

        List<List<Integer>> chunks = SqlIdChunks.partition(keys);

        assertEquals(1, chunks.size());
        assertEquals(keys, chunks.get(0));
    }

    @Test
    void partition_splitsLongerListsAtTheCapPreservingOrder() {
        List<Integer> keys = numbers(501);

        List<List<Integer>> chunks = SqlIdChunks.partition(keys);

        assertEquals(2, chunks.size());
        assertEquals(500, chunks.get(0).size());
        assertEquals(1, chunks.get(1).size());
        assertEquals(keys, chunks.stream().flatMap(List::stream).toList(),
                "partitioning must not drop, add or reorder values");
    }

    @Test
    void partition_ofMoreThanTwoChunks_neverExceedsTheCap() {
        List<Integer> chunksSizes = SqlIdChunks.partition(numbers(1001)).stream()
                .map(List::size)
                .toList();

        assertEquals(List.of(500, 500, 1), chunksSizes);
    }

    @Test
    void partition_ofAnEmptyList_hasNoChunks() {
        assertTrue(SqlIdChunks.partition(List.of()).isEmpty(),
                "an empty lookup must issue no statement at all");
    }

    @Test
    void placeholders_listsOneQuestionMarkPerValue() {
        assertEquals("?,?,?", SqlIdChunks.placeholders(3));
        assertEquals("?", SqlIdChunks.placeholders(1));
        assertEquals("", SqlIdChunks.placeholders(0));
    }

    @Test
    void maxInIds_staysBelowSqlitesVariableLimit() {
        assertTrue(SqlIdChunks.MAX_IN_IDS > 0 && SqlIdChunks.MAX_IN_IDS < 999,
                "older SQLite builds allow at most 999 host parameters; the chunk size must stay below it");
    }

    private static List<Integer> numbers(int count) {
        return IntStream.range(0, count).boxed().toList();
    }
}
