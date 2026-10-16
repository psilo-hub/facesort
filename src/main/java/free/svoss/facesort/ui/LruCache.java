package free.svoss.facesort.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A bounded memoizer: values are kept at most until the capacity is exceeded,
 * then the least recently read entry is dropped.
 *
 * <p>The View tab and the shared face-thumbnail cache use it to keep decoded
 * thumbnails keyed by a stable identity (an image hash; a face id plus the
 * crop's content), so re-rendering a grid decodes each JPEG once instead of
 * once per rebuild. The loader runs at most once per key while the entry is
 * resident.</p>
 *
 * <p>Instances are not thread-safe; any shared use must synchronize externally.
 * The UI-side caches read it on the UI thread.</p>
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

    /**
     * Returns the resident value for the given key without computing one, or
     * {@code null} when the key is not resident. A resident entry counts as a
     * read, so it survives the next eviction over an entry that was not read.
     *
     * <p>{@link #put} rejects null values, but {@link #get} stores whatever its
     * loader returns, so a {@code null} result does not prove the key is
     * absent.</p>
     *
     * @param key the key to look up; must not be null
     * @return the resident value, or {@code null} when absent
     */
    V getIfPresent(K key) {
        Objects.requireNonNull(key, "key");
        return entries.get(key);
    }

    /**
     * Stores a value under the given key, dropping the least recently read
     * entry when that puts the cache over capacity.
     *
     * @param key   the key to store under; must not be null
     * @param value the value to store; must not be null
     */
    void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        entries.put(key, value);
    }

    /**
     * Removes every entry, so a stale value cannot outlive the data it was
     * derived from.
     */
    void clear() {
        entries.clear();
    }
}
