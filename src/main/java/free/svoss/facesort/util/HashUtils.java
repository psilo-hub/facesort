package free.svoss.facesort.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class for computing SHA-256 file hashes.
 * Used to uniquely identify images by their content.
 */
public final class HashUtils {

    private static final int BUFFER_SIZE = 8192;
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private HashUtils() {
        // Utility class - not instantiable
    }

    /**
     * Computes the SHA-256 hash of a file and returns it as a lowercase hex string.
     *
     * @param file the path to the file to hash
     * @return the hex-encoded SHA-256 hash (64 characters)
     * @throws IOException if the file cannot be read
     */
    public static String hashFile(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all Java implementations
            throw new AssertionError("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Computes the SHA-256 hash of the given bytes and returns it as a
     * lowercase hex string.
     *
     * @param data the bytes to hash
     * @return the hex-encoded SHA-256 hash (64 characters)
     */
    public static String hashBytes(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return bytesToHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all Java implementations
            throw new AssertionError("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Converts a byte array to a lowercase hexadecimal string.
     *
     * @param bytes the byte array to convert
     * @return the hex-encoded string
     */
    static String bytesToHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = HEX_CHARS[v >>> 4];
            hex[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hex);
    }
}
