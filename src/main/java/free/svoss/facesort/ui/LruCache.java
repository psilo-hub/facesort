package free.svoss.facesort.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A bounded memoizer: values are kept at most until the capacity is exceeded,
 * then the least recently read entry is dropped.
 *
 * <p>The View tab uses it to keep decoded thumbnails keyed by a stable
 * identity (an image hash, a face id), so re-rendering a grid decodes each
 * JPEG once instead of once per rebuild. The loader runs at most once per
 * key while the entry is resident.</p>
 *
 * <p>Instances are not thread-safe; they are meant to be read on the UI
 * thread.</p>
 *
 * @param <K> the key type
 * @param <V> the value type
 */
final class LruCache<K, V> {

    private final int capacity;
    private final Map<K, V> entries;

    /**
     * Creates a cache that holds at most {@code capacity} entries.
     *
     * @param capacity the maximum number of entries, at least 1
     * @throws IllegalArgumentException when the capacity is not positive
     */
    LruCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        };
    }

    /**
     * Returns the value for the given key, loading it with {@code loader} when
     * it is not resident. The loaded value is stored before it is returned.
     *
     * @param key    the key to look up; must not be null
     * @param loader computes the value on a miss; must not be null
     * @return the resident or freshly loaded value
     */
    V get(K key, Supplier<V> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        if (entries.containsKey(key)) {
            return entries.get(key);
        }
        V value = loader.get();
        entries.put(key, value);
        return value;
    }
}
