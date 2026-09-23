package free.svoss.facesort.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EmbeddingUtils}.
 */
class EmbeddingUtilsTest {

    @Test
    void floatArray_roundTripsThroughBytes() {
        float[] original = new float[512];
        for (int i = 0; i < original.length; i++) {
            original[i] = (i * 0.5f - 100.0f);
        }

        byte[] bytes = EmbeddingUtils.floatArrayToBytes(original);
        assertEquals(512 * 4, bytes.length, "byte array must be 4 bytes per float");

        float[] restored = EmbeddingUtils.bytesToFloatArray(bytes);
        assertArrayEquals(original, restored, 0.0f, "round-trip must be lossless");
    }

    @Test
    void smallArray_roundTripsExactly() {
        float[] original = {1.0f, 2.5f, -3.75f, 0.0f};
        byte[] bytes = EmbeddingUtils.floatArrayToBytes(original);
        assertArrayEquals(original, EmbeddingUtils.bytesToFloatArray(bytes), 0.0f);
    }

    @Test
    void bytesToFloatArray_lengthNotMultipleOf4_throws() {
        assertThrows(IllegalArgumentException.class, () -> EmbeddingUtils.bytesToFloatArray(new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> EmbeddingUtils.bytesToFloatArray(new byte[5]));
    }

    @Test
    void emptyByteArray_decodesToEmptyFloatArray() {
        assertArrayEquals(new float[0], EmbeddingUtils.bytesToFloatArray(new byte[0]), 0.0f);
    }
}