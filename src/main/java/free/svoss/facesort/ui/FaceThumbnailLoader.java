package free.svoss.facesort.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Loads decoded face crops off the JavaFX application thread.
 *
 * <p>JavaFX can only load an {@code Image} in the background from a URL or a
 * stream path, not from an {@code InputStream} (JDK-8361286), so the crop JPEG
 * is decoded on a small daemon pool ({@link #decoderPool()}) and the decoded
 * value is published back on the JavaFX application thread through an injected
 * marshaller. The cache is consulted and updated only on that thread, so a
 * rebuilt grid reuses earlier decodes instead of decoding each crop again, and
 * a crop already being decoded is decoded only once.</p>
 *
 * <p>Every request names the destination that should display the crop (for the
 * face views, the reused {@code ImageView}). Only the latest crop requested for
 * a destination is delivered: a later request for the same destination
 * supersedes an earlier one, so a decode that finishes after being superseded
 * is dropped instead of overwriting a newer image.</p>
 *
 * <p>The decoder, the decode executor and the FX marshaller are injected, so
 * the loader can be exercised without a JavaFX toolkit.</p>
 *
 * @param <V> the decoded image type, e.g. {@code javafx.scene.image.Image}
 */
final class FaceThumbnailLoader<V> {

    private final FaceThumbnailCache<V> cache;
    private final Function<byte[], V> decoder;
    private final Executor decodeExecutor;
    private final Consumer<Runnable> fxRunner;
    private final Map<Long, List<Waiter<V>>> pending = new HashMap<>();
    private final Map<Object, Long> wanted = new WeakHashMap<>();

    FaceThumbnailLoader(int capacity, Function<byte[], V> decoder,
                        Executor decodeExecutor, Consumer<Runnable> fxRunner) {
        this.cache = new FaceThumbnailCache<>(capacity);
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.decodeExecutor = Objects.requireNonNull(decodeExecutor, "decodeExecutor");
        this.fxRunner = Objects.requireNonNull(fxRunner, "fxRunner");
    }

    /**
     * Delivers the decoded crop for the face to {@code onLoaded}, on the JavaFX
     * application thread, for {@code destination} to show. Must be called on
     * that thread. When the crop is absent or already cached the callback runs
     * before this method returns; otherwise the decode is queued on
     * {@code decodeExecutor} and a crop already being decoded is shared, so
     * concurrent requests decode it once.
     *
     * <p>Only the latest crop requested for {@code destination} is delivered: a
     * later request for the same destination supersedes an earlier one, so a
     * superseded decode is dropped rather than overwriting the newer crop.</p>
     *
     * @param destination the slot that will show the crop (the reused image view)
     * @param faceId      the face the crop belongs to
     * @param jpg         the stored crop, or null/empty when unavailable
     * @param onLoaded    receives the decoded crop, or {@code null} when unavailable
     */
    void load(Object destination, long faceId, byte[] jpg, Consumer<V> onLoaded) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(onLoaded, "onLoaded");
        if (jpg == null || jpg.length == 0) {
            wanted.remove(destination);
            deliver(onLoaded, null);
            return;
        }
        long cacheKey = FaceThumbnailCache.key(faceId, jpg);
        wanted.put(destination, cacheKey);
        V cached = cache.getIfPresent(faceId, jpg);
        if (cached != null) {
            deliver(onLoaded, cached);
            return;
        }
        List<Waiter<V>> waiting = pending.get(cacheKey);
        if (waiting != null) {
            waiting.add(new Waiter<>(destination, onLoaded));
            return;
        }
        List<Waiter<V>> waiters = new ArrayList<>();
        waiters.add(new Waiter<>(destination, onLoaded));
        pending.put(cacheKey, waiters);
        try {
            decodeExecutor.execute(() -> {
                V decoded;
                try {
                    decoded = decoder.apply(jpg);
                } catch (RuntimeException ex) {
                    decoded = null; // an undecodable crop shows as no thumbnail
                }
                V result = decoded;
                fxRunner.accept(() -> publish(faceId, jpg, cacheKey, result));
            });
        } catch (RuntimeException ex) {
            // the decode was never queued; report it instead of wedging the key
            pending.remove(cacheKey);
            deliver(onLoaded, null);
        }
    }

    /**
     * Stops delivering to a destination: a decode still on its way to it is
     * dropped. Used when a reused view is cleared before the crop arrives.
     *
     * @param destination the destination to forget; must not be null
     */
    void forget(Object destination) {
        Objects.requireNonNull(destination, "destination");
        wanted.remove(destination);
    }

    /** Runs on the FX thread: caches the decode and hands it to the waiters still wanting it. */
    private void publish(long faceId, byte[] jpg, long cacheKey, V decoded) {
        List<Waiter<V>> waiters = pending.remove(cacheKey);
        if (decoded != null) {
            cache.put(faceId, jpg, decoded);
        }
        if (waiters == null) {
            return;
        }
        for (Waiter<V> waiter : waiters) {
            if (stillWanted(waiter.destination, cacheKey)) {
                deliver(waiter.onLoaded, decoded);
            }
        }
    }

    private boolean stillWanted(Object destination, long cacheKey) {
        Long wantedKey = wanted.get(destination);
        return wantedKey != null && wantedKey == cacheKey;
    }

    private static <V> void deliver(Consumer<V> onLoaded, V decoded) {
        try {
            onLoaded.accept(decoded);
        } catch (RuntimeException ex) {
            // a broken callback must not stop the other waiters from being told
        }
    }

    /** A small daemon pool for JPEG decodes; decoding is CPU-bound. */
    static Executor decoderPool() {
        AtomicInteger index = new AtomicInteger();
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "face-thumbnail-decoder-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** A request to deliver a decode to one destination. */
    private static final class Waiter<V> {

        private final Object destination;
        private final Consumer<V> onLoaded;

        Waiter(Object destination, Consumer<V> onLoaded) {
            this.destination = destination;
            this.onLoaded = onLoaded;
        }
    }
}
