package free.svoss.facesort.ui;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Resolves the stored on-disk paths of a face's source image.
 *
 * <p>Every face card of a grid installs a path tooltip, so the lookups are
 * handed to one shared background thread instead of starting a thread per card,
 * and the resolved paths are remembered, so the faces of the same photo share
 * the result. A hash is normally looked up once per session; a failed lookup is
 * retried, and with a concurrent executor a racing request may repeat it. The
 * cache is small and bounded; only access to the map is serialized, because
 * {@link #clear()} is called from the UI thread while a lookup runs on the
 * background thread. No database query ever runs while the lock is held, so
 * neither the calling thread nor {@link #clear()} waits for one.</p>
 */
final class PathLookupCache {

    private final Executor executor;
    private final LruCache<String, List<String>> cache;
    private final Object cacheLock = new Object();

    /** Bumped by every {@link #clear()}; guarded by {@link #cacheLock}. */
    private long generation;

    /**
     * @param executor      runs the lookups; must not be null
     * @param cacheCapacity how many image hashes to remember, at least 1
     */
    PathLookupCache(Executor executor, int cacheCapacity) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.cache = new LruCache<>(cacheCapacity);
    }

    /**
     * Resolves the stored paths of {@code imageHash} and reports them to
     * {@code onResult}, or any failure to {@code onFailure}. A remembered hash
     * is reported at once on the calling thread; otherwise the lookup is queued
     * and its result or failure is reported on the lookup thread. Both
     * callbacks must not block.
     *
     * <p>A lookup that fails is not remembered, so a later request retries. With
     * the shared single-threaded executor a request that missed while an earlier
     * one was still running is served from the cache; a concurrent executor
     * could repeat the lookup, which is a harmless extra read.</p>
     *
     * @param imageHash the source image hash; must not be null
     * @param lookup    the database lookup; must not be null
     * @param onResult  receives the stored paths, possibly empty
     * @param onFailure receives a lookup failure
     */
    void lookup(String imageHash, FaceUi.Lookup<List<String>> lookup,
                Consumer<List<String>> onResult, Consumer<Throwable> onFailure) {
        Objects.requireNonNull(imageHash, "imageHash");
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(onResult, "onResult");
        Objects.requireNonNull(onFailure, "onFailure");

        List<String> remembered = rememberedPaths(imageHash);
        if (remembered != null) {
            onResult.accept(remembered);
            return;
        }
        long seen = invalidationGeneration();
        executor.execute(() -> resolve(imageHash, lookup, onResult, onFailure, seen));
    }

    /** Forgets every remembered path, so the next hover looks it up again. */
    void clear() {
        synchronized (cacheLock) {
            generation++;
            cache.clear();
        }
    }

    /**
     * Looks the hash up on the lookup thread and remembers it. The cache is
     * checked again first, so a request that missed while an earlier one was
     * still running is served from the cache instead of repeating the lookup.
     * The lookup itself runs without the cache lock, so neither the calling
     * thread nor {@link #clear()} ever waits for a database query. A result read
     * in a later generation than {@code seen} is still reported, so the caller
     * receives what the hover read, but it is not remembered, because an
     * invalidation superseded it.
     */
    private void resolve(String imageHash, FaceUi.Lookup<List<String>> lookup,
                         Consumer<List<String>> onResult, Consumer<Throwable> onFailure,
                         long seen) {
        List<String> paths = rememberedPaths(imageHash);
        if (paths == null) {
            try {
                paths = Objects.requireNonNull(lookup.byHash(imageHash), "paths");
            } catch (Exception ex) {
                onFailure.accept(ex);
                return;
            }
            rememberIfUnchanged(imageHash, paths, seen);
        }
        onResult.accept(paths);
    }

    private List<String> rememberedPaths(String imageHash) {
        synchronized (cacheLock) {
            return cache.getIfPresent(imageHash);
        }
    }

    private long invalidationGeneration() {
        synchronized (cacheLock) {
            return generation;
        }
    }

    private void rememberIfUnchanged(String imageHash, List<String> paths, long seenGeneration) {
        synchronized (cacheLock) {
            if (generation == seenGeneration) {
                cache.put(imageHash, paths);
            }
        }
    }
}
