package free.svoss.facesort.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HashUtils}.
 */
class HashUtilsTest {

    @TempDir
    Path tempDir;

    private Path writeFile(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void sameFileHashedTwice_returnsSameHash() throws Exception {
        Path file = writeFile("a.txt", "hello world");
        String first = HashUtils.hashFile(file);
        String second = HashUtils.hashFile(file);
        assertEquals(first, second, "hashing the same file twice must be deterministic");
    }

    @Test
    void differentFiles_produceDifferentHashes() throws Exception {
        Path a = writeFile("a.txt", "content A");
        Path b = writeFile("b.txt", "content B");
        assertNotEquals(HashUtils.hashFile(a), HashUtils.hashFile(b),
                "files with different content must not collide");
    }

    @Test
    void sameContentDifferentFiles_produceSameHash() throws Exception {
        Path a = writeFile("a.txt", "identical content");
        Path b = writeFile("b.txt", "identical content");
        assertEquals(HashUtils.hashFile(a), HashUtils.hashFile(b),
                "content-based hash must ignore file location");
    }

    @Test
    void hash_is64LowercaseHexChars() throws Exception {
        Path file = writeFile("c.txt", "anything");
        String hash = HashUtils.hashFile(file);
        assertTrue(hash.matches("[0-9a-f]{64}"),
                "expected 64 lowercase hex characters, got: " + hash);
    }

    @Test
    void hashFile_missingFile_throwsIOException() {
        Path missing = tempDir.resolve("definitely-missing.txt");
        assertThrows(java.io.IOException.class, () -> HashUtils.hashFile(missing));
    }

    @Test
    void hashBytes_hashesKnownBytesDeterministically() {
        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
        String first = HashUtils.hashBytes(bytes);
        String second = HashUtils.hashBytes(bytes);
        assertEquals(first, second, "hashing the same bytes twice must be deterministic");
        assertTrue(first.matches("[0-9a-f]{64}"), "expected 64 lowercase hex characters, got: " + first);
    }

    @Test
    void hashBytes_differentContent_differentHash() {
        assertNotEquals(
                HashUtils.hashBytes("content A".getBytes(StandardCharsets.UTF_8)),
                HashUtils.hashBytes("content B".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void hashBytes_andHashFile_agreeOnSameContent() throws Exception {
        Path file = writeFile("same.txt", "shared content");
        byte[] bytes = Files.readAllBytes(file);
        assertEquals(HashUtils.hashFile(file), HashUtils.hashBytes(bytes),
                "bytes hash must match the file hash of the same content");
    }

    @Test
    void bytesToHex_encodesKnownBytes() {
        // 0x00 0xFF 0x10 -> "00ff10"
        assertEquals("00ff10", HashUtils.bytesToHex(new byte[]{0x00, (byte) 0xFF, 0x10}));
    }
}