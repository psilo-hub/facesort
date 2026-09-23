package free.svoss.facesort.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility class for serializing and deserializing float arrays (face embeddings).
 */
public final class EmbeddingUtils {

    private static final int BYTES_PER_FLOAT = 4;

    private EmbeddingUtils() {
        // Utility class - not instantiable
    }

    /**
     * Converts a float array to a byte array using big-endian byte order.
     * The resulting array length is exactly {@code arr.length * 4}.
     *
     * @param arr the float array to convert
     * @return the byte array representation
     */
    public static byte[] floatArrayToBytes(float[] arr) {
        ByteBuffer buffer = ByteBuffer.allocate(arr.length * BYTES_PER_FLOAT);
        buffer.order(ByteOrder.BIG_ENDIAN);
        for (float f : arr) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    /**
     * Converts a byte array to a float array using big-endian byte order.
     * The byte array length must be a multiple of 4.
     *
     * @param bytes the byte array to convert
     * @return the float array representation
     * @throws IllegalArgumentException if the byte array length is not a multiple of 4
     */
    public static float[] bytesToFloatArray(byte[] bytes) {
        if (bytes.length % BYTES_PER_FLOAT != 0) {
            throw new IllegalArgumentException(
                    "Byte array length must be a multiple of 4, got: " + bytes.length);
        }
        int floatCount = bytes.length / BYTES_PER_FLOAT;
        float[] result = new float[floatCount];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < floatCount; i++) {
            result[i] = buffer.getFloat();
        }
        return result;
    }
}
