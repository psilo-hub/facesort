package free.svoss.facesort.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility class for serializing and deserializing float arrays (face embeddings)
 * and computing similarity metrics.
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

    /**
     * Computes the cosine similarity between two float vectors.
     * Both vectors must have the same length.
     *
     * @param a the first vector
     * @param b the second vector
     * @return the cosine similarity in the range [-1.0, 1.0],
     *         or 0.0 if either vector is zero-length
     * @throws IllegalArgumentException if the vectors have different lengths
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Vectors must have the same length: " + a.length + " vs " + b.length);
        }

        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0.0f || normB == 0.0f) {
            return 0.0f;
        }

        return dotProduct / (float) (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
