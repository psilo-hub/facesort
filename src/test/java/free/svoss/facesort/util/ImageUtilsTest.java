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
}