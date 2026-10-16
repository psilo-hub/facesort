package free.svoss.facesort.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link FaceThumbnailCache}, the shared cache of decoded face
 * thumbs keyed by face id and crop content. A {@code String} value type with a
 * counting decoder stands in for the decoded thumbnail, because building a
 * {@code javafx.scene.image.Image} needs a JavaFX toolkit.
 */
class FaceThumbnailCacheTest {

    private final AtomicInteger decodes = new AtomicInteger();

    private FaceThumbnailCache<String> cache(int capacity) {
        return new FaceThumbnailCache<>(capacity, bytes -> {
            decodes.incrementAndGet();
            return new String(bytes, StandardCharsets.UTF_8);
        });
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void sameFaceIdIsDecodedOnlyOnce() {
        FaceThumbnailCache<String> cache = cache(10);

        String first = cache.get(1L, bytes("face-1"));
        String second = cache.get(1L, bytes("face-1"));

        assertEquals("face-1", first);
        assertSame(first, second, "a resident face id must return the decoded thumbnail");
        assertEquals(1, decodes.get(), "a resident face id must not be decoded twice");
    }

    @Test
    void differentFaceIdsAreDecodedSeparately() {
        FaceThumbnailCache<String> cache = cache(10);

        assertEquals("face-1", cache.get(1L, bytes("face-1")));
        assertEquals("face-2", cache.get(2L, bytes("face-2")));

        assertEquals(2, decodes.get(), "each face id must be decoded on its own");
    }

    @Test
    void nullBytesReturnNullWithoutDecoding() {
        FaceThumbnailCache<String> cache = cache(10);

        assertNull(cache.get(1L, null), "a face without a crop has no thumbnail");

        assertEquals(0, decodes.get(), "there is nothing to decode for null bytes");
    }

    @Test
    void emptyBytesReturnNullWithoutDecoding() {
        FaceThumbnailCache<String> cache = cache(10);

        assertNull(cache.get(1L, new byte[0]), "an empty crop has no thumbnail");

        assertEquals(0, decodes.get(), "there is nothing to decode for empty bytes");
    }

    @Test
    void anEvictedEntryIsDecodedAgain() {
        FaceThumbnailCache<String> cache = cache(2);
        cache.get(1L, bytes("face-1"));
        cache.get(2L, bytes("face-2"));
        cache.get(3L, bytes("face-3"));
        assertEquals(3, decodes.get());

        decodes.set(0);
        assertEquals("face-1", cache.get(1L, bytes("face-1")));

        assertEquals(1, decodes.get(), "the oldest face id must have been evicted");
    }

    @Test
    void aHitRefreshesTheEntrySoItSurvivesTheNextEviction() {
        FaceThumbnailCache<String> cache = cache(2);
        cache.get(1L, bytes("face-1"));
        cache.get(2L, bytes("face-2"));

        cache.get(1L, bytes("face-1"));
        cache.get(3L, bytes("face-3"));

        decodes.set(0);
        assertEquals("face-1", cache.get(1L, bytes("face-1")));
        assertEquals(0, decodes.get(), "the re-read face id must still be resident");

        assertEquals("face-2", cache.get(2L, bytes("face-2")));
        assertEquals(1, decodes.get(), "the untouched face id must be the one evicted");
    }

    @Test
    void aChangedCropForTheSameFaceIdIsDecodedAgain() {
        FaceThumbnailCache<String> cache = cache(10);

        assertEquals("rotated", cache.get(1L, bytes("rotated")));
        assertEquals("upright", cache.get(1L, bytes("upright")));

        assertEquals(2, decodes.get(),
                "a crop rewritten by the face-rotation migration must be decoded afresh");
    }

    @Test
    void nullDecoderIsRejected() {
        assertThrows(NullPointerException.class, () -> new FaceThumbnailCache<String>(10, null));
    }

    @Test
    void nonPositiveCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new FaceThumbnailCache<String>(0, bytes -> "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new FaceThumbnailCache<String>(-1, bytes -> "x"));
    }
}
