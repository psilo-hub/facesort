package free.svoss.facesort.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ImageUtils}.
 */
class ImageUtilsTest {

    @TempDir
    Path tempDir;

    private static BufferedImage solidImage(int width, int height, Color color) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void writeIco(Path file, Color color, int... sizes) throws IOException {
        List<byte[]> payloads = new ArrayList<>();
        for (int size : sizes) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(solidImage(size, size, color), "png", png);
            payloads.add(png.toByteArray());
        }

        int headerSize = 6 + 16 * sizes.length;
        int total = headerSize + payloads.stream().mapToInt(payload -> payload.length).sum();
        ByteBuffer ico = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0);              // reserved
        ico.putShort((short) 1);              // resource type: icon
        ico.putShort((short) sizes.length);   // number of images
        int offset = headerSize;
        for (int i = 0; i < sizes.length; i++) {
            byte[] payload = payloads.get(i);
            ico.put((byte) sizes[i]);         // width (0 would mean 256)
            ico.put((byte) sizes[i]);         // height
            ico.put((byte) 0);                // palette size
            ico.put((byte) 0);                // reserved
            ico.putShort((short) 1);          // color planes
            ico.putShort((short) 32);         // bits per pixel
            ico.putInt(payload.length);       // payload size in bytes
            ico.putInt(offset);               // payload offset
            offset += payload.length;
        }
        for (byte[] payload : payloads) {
            ico.put(payload);
        }
        Files.write(file, ico.array());
    }

    @Test
    void downsize_preservesAspectRatio() {
        BufferedImage img = solidImage(800, 400, Color.RED);
        BufferedImage resized = ImageUtils.downsize(img, 200);
        assertEquals(200, resized.getWidth(), "width must be capped at maxDimension");
        assertEquals(100, resized.getHeight(), "height must preserve 2:1 aspect ratio");
    }

    @Test
    void downsize_landscapeKeepsMaxDimension() {
        BufferedImage img = solidImage(1000, 500, Color.BLUE);
        BufferedImage resized = ImageUtils.downsize(img, 120);
        assertTrue(Math.abs(resized.getWidth() - 120) <= 1, "width ~120, got " + resized.getWidth());
        assertTrue(Math.abs(resized.getHeight() - 60) <= 1, "height ~60, got " + resized.getHeight());
    }

    @Test
    void downsize_portraitKeepsMaxDimension() {
        BufferedImage img = solidImage(300, 600, Color.GREEN);
        BufferedImage resized = ImageUtils.downsize(img, 150);
        assertTrue(Math.abs(resized.getHeight() - 150) <= 1, "height ~150, got " + resized.getHeight());
        assertTrue(Math.abs(resized.getWidth() - 75) <= 1, "width ~75, got " + resized.getWidth());
    }

    @Test
    void downsize_alreadySmall_returnsSameImage() {
        BufferedImage img = solidImage(50, 30, Color.BLACK);
        BufferedImage resized = ImageUtils.downsize(img, 200);
        assertEquals(50, resized.getWidth());
        assertEquals(30, resized.getHeight());
    }

    @Test
    void toJpegBytes_roundTripsThroughImageIO() throws Exception {
        BufferedImage img = solidImage(20, 30, Color.ORANGE);
        byte[] jpeg = ImageUtils.toJpegBytes(img, 0.9f);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertTrue(decoded != null, "decoded image must not be null");
        assertEquals(20, decoded.getWidth());
        assertEquals(30, decoded.getHeight());
    }

    @Test
    void readImage_validPng_returnsImage() throws Exception {
        Path file = tempDir.resolve("img.png");
        ImageIO.write(solidImage(8, 8, Color.CYAN), "png", file.toFile());
        BufferedImage img = ImageUtils.readImage(file);
        assertEquals(8, img.getWidth());
        assertEquals(8, img.getHeight());
    }

    @Test
    void readImage_validBmp_returnsImage() throws Exception {
        Path file = tempDir.resolve("img.bmp");
        assertTrue(ImageIO.write(solidImage(12, 10, Color.ORANGE), "bmp", file.toFile()),
                "BMP write must succeed");
        BufferedImage img = ImageUtils.readImage(file);
        assertEquals(12, img.getWidth());
        assertEquals(10, img.getHeight());
    }

    @Test
    void readImage_validGif_returnsImage() throws Exception {
        Path file = tempDir.resolve("img.gif");
        assertTrue(ImageIO.write(solidImage(9, 7, Color.GREEN), "gif", file.toFile()),
                "GIF write must succeed");
        BufferedImage img = ImageUtils.readImage(file);
        assertEquals(9, img.getWidth());
        assertEquals(7, img.getHeight());
    }

    @Test
    void readImage_validIco_returnsImage() throws Exception {
        Path file = tempDir.resolve("icon.ico");
        writeIco(file, Color.MAGENTA, 64);
        BufferedImage img = ImageUtils.readImage(file);
        assertEquals(64, img.getWidth());
        assertEquals(64, img.getHeight());
    }

    @Test
    void readImage_multiSizeIco_usesLargestImage() throws Exception {
        Path file = tempDir.resolve("multi.ico");
        writeIco(file, Color.MAGENTA, 16, 64, 32);
        BufferedImage img = ImageUtils.readImage(file);
        assertEquals(64, img.getWidth(), "the largest icon size must be read");
        assertEquals(64, img.getHeight(), "the largest icon size must be read");
    }

    @Test
    void readImage_nonImageFile_throwsIOException() throws Exception {
        Path file = tempDir.resolve("not-an-image.txt");
        Files.write(file, Arrays.asList("plain text, not an image"), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> ImageUtils.readImage(file));
    }

    @Test
    void readImage_exifOrientation1_normal() throws Exception {
        Path file = writeJpegWithOrientation(tempDir.resolve("orient1.jpg"), 100, 50, 1);
        BufferedImage img = ImageUtils.readImage(file);
        assertEquals(100, img.getWidth(), "orientation 1: width unchanged");
        assertEquals(50, img.getHeight(), "orientation 1: height unchanged");
    }

    @Test
    void readImage_exifOrientation3_rotate180() throws Exception {
        Path file = writeJpegWithOrientation(tempDir.resolve("orient3.jpg"), 100, 50, 3);
        BufferedImage img = ImageUtils.readImage(file);
        assertEquals(100, img.getWidth(), "orientation 3: width unchanged");
        assertEquals(50, img.getHeight(), "orientation 3: height unchanged");
    }

    @Test
    void readImage_exifOrientation6_rotate90CW() throws Exception {
        Path file = writeJpegWithOrientation(tempDir.resolve("orient6.jpg"), 100, 50, 6);
        BufferedImage img = ImageUtils.readImage(file);
        assertEquals(50, img.getWidth(), "orientation 6: width and height swapped");
        assertEquals(100, img.getHeight(), "orientation 6: width and height swapped");
    }

    @Test
    void readImage_exifOrientation8_rotate90CCW() throws Exception {
        Path file = writeJpegWithOrientation(tempDir.resolve("orient8.jpg"), 100, 50, 8);
        BufferedImage img = ImageUtils.readImage(file);
        assertEquals(50, img.getWidth(), "orientation 8: width and height swapped");
        assertEquals(100, img.getHeight(), "orientation 8: width and height swapped");
    }

    @Test
    void readImage_exifOrientation2_flipHorizontal() throws Exception {
        Path file = writeJpegWithOrientation(tempDir.resolve("orient2.jpg"), 100, 50, 2);
        BufferedImage img = ImageUtils.readImage(file);
        assertEquals(100, img.getWidth(), "orientation 2: width unchanged");
        assertEquals(50, img.getHeight(), "orientation 2: height unchanged");
    }

    @Test
    void readImage_exifOrientation4_flipVertical() throws Exception {
        Path file = writeJpegWithOrientation(tempDir.resolve("orient4.jpg"), 100, 50, 4);
        BufferedImage img = ImageUtils.readImage(file);
        assertEquals(100, img.getWidth(), "orientation 4: width unchanged");
        assertEquals(50, img.getHeight(), "orientation 4: height unchanged");
    }

    private static Path writeJpegWithOrientation(Path file, int width, int height, int orientation) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            g.setColor(java.awt.Color.RED);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }

        // Write JPEG to byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        byte[] jpegBytes = baos.toByteArray();

        // Build EXIF APP1 segment with orientation tag (TIFF little-endian)
        ByteArrayOutputStream exif = new ByteArrayOutputStream();
        exif.write(new byte[]{0x45, 0x78, 0x69, 0x66, 0x00, 0x00});
        // TIFF header: little-endian byte order, magic 42, offset to IFD0 = 8
        exif.write(new byte[]{(byte) 0x49, (byte) 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00});
        // IFD0: 1 entry
        exif.write(new byte[]{0x01, 0x00});
        // Entry: tag=0x0112 (orientation), type=3 (SHORT), count=1, value=orientation
        exif.write(new byte[]{(byte) 0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00});
        exif.write(orientation & 0xFF);
        exif.write((orientation >> 8) & 0xFF);
        exif.write(0);
        exif.write(0);
        // Offset to next IFD = 0
        exif.write(new byte[]{0x00, 0x00, 0x00, 0x00});

        byte[] exifBytes = exif.toByteArray();

        // Insert APP1 segment after APP0 (JFIF must be first after SOI)
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(jpegBytes, 0, 2); // SOI

        // Find APP0 marker (FF E0) after SOI
        int app0Pos = 2;
        while (app0Pos < jpegBytes.length - 1) {
            if (jpegBytes[app0Pos] == (byte) 0xFF && jpegBytes[app0Pos + 1] == (byte) 0xE0) {
                break;
            }
            app0Pos++;
        }

        // Copy everything up to and including APP0 segment
        if (app0Pos < jpegBytes.length - 1) {
            // APP0 segment: 2 bytes marker + 2 bytes length + data
            int app0Len = ((jpegBytes[app0Pos + 2] & 0xFF) << 8) | (jpegBytes[app0Pos + 3] & 0xFF);
            int app0End = app0Pos + 2 + app0Len;
            result.write(jpegBytes, 2, app0End - 2);
        } else {
            result.write(jpegBytes, 2, jpegBytes.length - 2);
        }

        // APP1 marker
        result.write(0xFF);
        result.write(0xE1);
        result.write((exifBytes.length + 2) >> 8);
        result.write((exifBytes.length + 2) & 0xFF); // length including this field
        result.write(exifBytes);

        // Rest of JPEG after APP0
        if (app0Pos < jpegBytes.length - 1) {
            int app0Len = ((jpegBytes[app0Pos + 2] & 0xFF) << 8) | (jpegBytes[app0Pos + 3] & 0xFF);
            int app0End = app0Pos + 2 + app0Len;
            result.write(jpegBytes, app0End, jpegBytes.length - app0End);
        }

        Files.write(file, result.toByteArray());
        return file;
    }
}