package free.svoss.facesort.ui;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * A bounded cache of decoded face thumbnails keyed by face id and crop content.
 *
 * <p>A face id is stable, but the stored crop can be rewritten once by the
 * face-rotation migration; the key therefore also folds in a hash of the crop
 * bytes, so a rewritten crop is a different key and is decoded afresh. All face
 * views share one instance through {@link FaceUi}, so rebuilding a face grid
 * reuses the JPEG decoded for an earlier rebuild instead of decoding it again.</p>
 *
 * <p>The decoder is injected so the cache can be exercised without a JavaFX
 * toolkit (building a {@code javafx.scene.image.Image} would need one), and so
 * the cache itself does not depend on the UI toolkit.</p>
 *
 * <p>Instances are not thread-safe; they are meant to be used on the thread
 * that owns the decoded images (the JavaFX application thread).</p>
 *
 * @param <V> the decoded image type, e.g. {@code javafx.scene.image.Image}
 */
final class FaceThumbnailCache<V> {

    private final LruCache<Long, V> cache;
    private final Function<byte[], V> decoder;

    /**
     * Creates a cache that holds at most {@code capacity} decoded images,
     * decoding misses with {@code decoder}.
     *
     * @param capacity the maximum number of entries, at least 1
     * @param decoder  turns encoded JPEG bytes into a decoded image; must not be null
     */
    FaceThumbnailCache(int capacity, Function<byte[], V> decoder) {
        this.cache = new LruCache<>(capacity);
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Returns the decoded thumbnail for a face, decoding it on the first read
     * and reusing it on every later read.
     *
     * @param faceId id of the face; part of the cache key, the crop content is
     *               the other part
     * @param jpg    the encoded crop, may be null or empty
     * @return the decoded image, or null when there is nothing to decode
     */
    V get(long faceId, byte[] jpg) {
        if (jpg == null || jpg.length == 0) {
            return null;
        }
        return cache.get(cacheKey(faceId, jpg), () -> decoder.apply(jpg));
    }

    /**
     * A face id combined with a hash of its crop bytes: the id keeps different
     * faces apart and the content hash makes a rewritten crop a different key.
     * That matters because the one-time face-rotation migration replaces a
     * stored crop under its existing face id; without the content hash the
     * cache would keep serving the pre-migration crop.
     */
    private static long cacheKey(long faceId, byte[] jpg) {
        return (faceId << 32) | (Arrays.hashCode(jpg) & 0xFFFFFFFFL);
    }
}
