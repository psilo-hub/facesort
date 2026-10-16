package free.svoss.facesort.ui;

import java.util.Arrays;

/**
 * A bounded store of decoded face crops, keyed by the face id and the crop
 * content.
 *
 * <p>A face id is stable, but the stored crop can be rewritten once by the
 * face-rotation migration; the key therefore also folds in a hash of the crop
 * bytes, so a rewritten crop is a different key and is decoded afresh. The
 * store only holds already-decoded values; {@link FaceThumbnailLoader} fills it
 * after decoding off the JavaFX application thread.</p>
 *
 * <p>Instances are not thread-safe; they are accessed only on the JavaFX
 * application thread.</p>
 *
 * @param <V> the decoded image type, e.g. {@code javafx.scene.image.Image}
 */
final class FaceThumbnailCache<V> {

    private final LruCache<Long, V> entries;

    FaceThumbnailCache(int capacity) {
        entries = new LruCache<>(capacity);
    }

    /** Returns the cached decode for the crop, or {@code null} when none is resident. */
    V getIfPresent(long faceId, byte[] jpg) {
        if (jpg == null || jpg.length == 0) {
            return null;
        }
        return entries.getIfPresent(key(faceId, jpg));
    }

    /**
     * Stores a decode for the crop. A null or empty crop is ignored, mirroring
     * {@link #getIfPresent(long, byte[])}: it could never be looked up again.
     */
    void put(long faceId, byte[] jpg, V value) {
        if (jpg == null || jpg.length == 0) {
            return;
        }
        entries.put(key(faceId, jpg), value);
    }

    /**
     * The identity of a face crop: the face id combined with a hash of the crop
     * bytes. The id keeps different faces apart; the content hash makes a crop
     * rewritten under its existing face id (the one-time face-rotation migration)
     * a different key, so it is decoded afresh.
     */
    static long key(long faceId, byte[] jpg) {
        return (faceId << 32) | (Arrays.hashCode(jpg) & 0xFFFFFFFFL);
    }
}
