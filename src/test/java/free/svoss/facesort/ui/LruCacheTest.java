package free.svoss.facesort.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link LruCache}, the bounded memoizer the View tab uses to keep
 * decoded thumbnails across grid rebuilds.
 */
class LruCacheTest {

    private final AtomicInteger loads = new AtomicInteger();

    private LruCache<String, String> cache(int capacity) {
        return new LruCache<>(capacity);
    }

    private String load(String key) {
        loads.incrementAndGet();
        return key.toUpperCase();
    }

    @Test
    void aHitReturnsTheCachedValueWithoutLoadingAgain() {
        LruCache<String, String> cache = cache(10);

        assertEquals("FACE:1", cache.get("face:1", () -> load("face:1")));
        assertEquals("FACE:1", cache.get("face:1", () -> load("face:1")));

        assertEquals(1, loads.get(), "a resident key must not be decoded twice");
    }

    @Test
    void differentKeysAreLoadedSeparately() {
        LruCache<String, String> cache = cache(10);

        assertEquals("FACE:1", cache.get("face:1", () -> load("face:1")));
        assertEquals("FACE:2", cache.get("face:2", () -> load("face:2")));

        assertEquals(2, loads.get());
    }

    @Test
    void theLeastRecentlyUsedEntryIsEvictedAtCapacity() {
        LruCache<String, String> cache = cache(2);
        cache.get("face:1", () -> load("face:1"));
        cache.get("face:2", () -> load("face:2"));
        cache.get("face:3", () -> load("face:3"));
        assertEquals(3, loads.get());

        loads.set(0);
        cache.get("face:1", () -> load("face:1"));

        assertEquals(1, loads.get(), "the oldest key must have been evicted");
    }

    @Test
    void aHitRefreshesTheEntrySoItSurvivesTheNextEviction() {
        LruCache<String, String> cache = cache(2);
        cache.get("face:1", () -> load("face:1"));
        cache.get("face:2", () -> load("face:2"));

        cache.get("face:1", () -> load("face:1"));
        cache.get("face:3", () -> load("face:3"));

        loads.set(0);
        assertEquals("FACE:1", cache.get("face:1", () -> load("face:1")));
        assertEquals(0, loads.get(), "the re-read key must still be resident");

        assertEquals("FACE:2", cache.get("face:2", () -> load("face:2")));
        assertEquals(1, loads.get(), "the untouched key must be the one evicted");
    }

    @Test
    void nullLoaderResultsAreCachedLikeAnyOtherValue() {
        LruCache<String, String> cache = cache(10);

        assertNull(cache.get("missing", () -> {
            loads.incrementAndGet();
            return null;
        }));
        assertNull(cache.get("missing", () -> {
            loads.incrementAndGet();
            return null;
        }));

        assertEquals(1, loads.get(), "a cached miss must not re-run the loader");
    }

    @Test
    void nonPositiveCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LruCache<String, String>(0));
        assertThrows(IllegalArgumentException.class, () -> new LruCache<String, String>(-1));
    }

    @Test
    void nullKeyOrLoaderIsRejected() {
        LruCache<String, String> cache = cache(10);
        assertThrows(NullPointerException.class, () -> cache.get(null, () -> "x"));
        assertThrows(NullPointerException.class, () -> cache.get("face:1", null));
    }
}
