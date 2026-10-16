package free.svoss.facesort.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link FaceThumbnailCache}, the shared store of decoded face thumbs
 * keyed by face id and crop content. A {@code String} value type stands in for
 * the decoded thumbnail, because building a {@code javafx.scene.image.Image}
 * needs a JavaFX toolkit.
 */
class FaceThumbnailCacheTest {

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aStoredDecodeIsReturned() {
        FaceThumbnailCache<String> cache = new FaceThumbnailCache<>(10);

        cache.put(1L, bytes("a"), "decoded");

        assertEquals("decoded", cache.getIfPresent(1L, bytes("a")));
    }

    @Test
    void aChangedCropForTheSameFaceIdIsNotReturned() {
        FaceThumbnailCache<String> cache = new FaceThumbnailCache<>(10);
        cache.put(1L, bytes("rotated"), "rotated-decode");

        assertNull(cache.getIfPresent(1L, bytes("upright")),
                "a crop rewritten under its existing face id must not reuse the old decode");
        assertEquals("rotated-decode", cache.getIfPresent(1L, bytes("rotated")),
                "the original crop must still be resident under its own key");
    }

    @Test
    void differentFaceIdsAreKeptApart() {
        FaceThumbnailCache<String> cache = new FaceThumbnailCache<>(10);
        cache.put(1L, bytes("same"), "first");
        cache.put(2L, bytes("same"), "second");

        assertEquals("first", cache.getIfPresent(1L, bytes("same")));
        assertEquals("second", cache.getIfPresent(2L, bytes("same")));
    }

    @Test
    void aNullCropIsNotResident() {
        FaceThumbnailCache<String> cache = new FaceThumbnailCache<>(10);

        assertNull(cache.getIfPresent(1L, null), "a face without a crop has no decode");
    }

    @Test
    void anEmptyCropIsNotResident() {
        FaceThumbnailCache<String> cache = new FaceThumbnailCache<>(10);

        assertNull(cache.getIfPresent(1L, new byte[0]), "an empty crop has no decode");
    }

    @Test
    void aNullCropIsNotStored() {
        FaceThumbnailCache<String> cache = new FaceThumbnailCache<>(1);
        cache.put(1L, bytes("a"), "A");

        cache.put(2L, null, "ignored");

        assertEquals("A", cache.getIfPresent(1L, bytes("a")),
                "an ignored null-crop store must not evict a resident decode");
        assertNull(cache.getIfPresent(2L, null), "a null crop could never be looked up again");
    }

    @Test
    void anEmptyCropIsNotStored() {
        FaceThumbnailCache<String> cache = new FaceThumbnailCache<>(1);
        cache.put(1L, bytes("a"), "A");

        cache.put(2L, new byte[0], "ignored");

        assertEquals("A", cache.getIfPresent(1L, bytes("a")),
                "an ignored empty-crop store must not evict a resident decode");
        assertNull(cache.getIfPresent(2L, new byte[0]), "an empty crop could never be looked up again");
    }

    @Test
    void theLeastRecentlyUsedEntryIsEvictedAtCapacity() {
        FaceThumbnailCache<String> cache = new FaceThumbnailCache<>(2);
        cache.put(1L, bytes("a"), "A");
        cache.put(2L, bytes("b"), "B");

        cache.getIfPresent(1L, bytes("a"));
        cache.put(3L, bytes("c"), "C");

        assertEquals("A", cache.getIfPresent(1L, bytes("a")),
                "the re-read crop must still be resident");
        assertNull(cache.getIfPresent(2L, bytes("b")),
                "the untouched crop must be the one evicted");
    }

    @Test
    void nonPositiveCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FaceThumbnailCache<String>(0));
        assertThrows(IllegalArgumentException.class, () -> new FaceThumbnailCache<String>(-1));
    }
}
