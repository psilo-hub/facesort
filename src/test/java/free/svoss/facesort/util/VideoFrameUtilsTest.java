package free.svoss.facesort.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link VideoFrameUtils} — conversion of packed RGB24 frame bytes
 * (as delivered by ffmpeg decoders) into {@link BufferedImage}s.
 */
class VideoFrameUtilsTest {

    private static int argb(int r, int g, int b) {
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    /** Builds row-major RGB24 bytes, three bytes per pixel, from a color function. */
    private static byte[] buildRgb24(int width, int height,
                                     BiFunction<Integer, Integer, int[]> pixelColor) {
        byte[] data = new byte[width * height * 3];
        int p = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int[] rgb = pixelColor.apply(x, y);
                data[p++] = (byte) rgb[0];
                data[p++] = (byte) rgb[1];
                data[p++] = (byte) rgb[2];
            }
        }
        return data;
    }

    @Test
    void twoByTwo_cornersMatchExpectedColors() {
        byte[] data = buildRgb24(2, 2, (x, y) -> new int[]{x * 100, y * 100, 50});
        BufferedImage img = VideoFrameUtils.rgb24ToImage(data, 2, 2);

        assertEquals(2, img.getWidth());
        assertEquals(2, img.getHeight());
        assertEquals(argb(0, 0, 50), img.getRGB(0, 0));
        assertEquals(argb(100, 0, 50), img.getRGB(1, 0));
        assertEquals(argb(0, 100, 50), img.getRGB(0, 1));
        assertEquals(argb(100, 100, 50), img.getRGB(1, 1));
    }

    @Test
    void threeByThree_middlePixelMatches() {
        int width = 3;
        int height = 3;
        byte[] data = buildRgb24(width, height,
                (x, y) -> x == 1 && y == 1 ? new int[]{255, 0, 0} : new int[]{0, 0, 0});
        BufferedImage img = VideoFrameUtils.rgb24ToImage(data, width, height);

        assertEquals(argb(255, 0, 0), img.getRGB(1, 1));
        assertEquals(argb(0, 0, 0), img.getRGB(0, 0));
        assertEquals(argb(0, 0, 0), img.getRGB(2, 2));
    }

    @Test
    void singleRow_isRowMajorRgb() {
        byte[] data = new byte[]{
                (byte) 10, (byte) 20, (byte) 30,
                (byte) 40, (byte) 50, (byte) 60,
                (byte) 70, (byte) 80, (byte) 90};
        BufferedImage img = VideoFrameUtils.rgb24ToImage(data, 3, 1);

        assertEquals(argb(10, 20, 30), img.getRGB(0, 0));
        assertEquals(argb(40, 50, 60), img.getRGB(1, 0));
        assertEquals(argb(70, 80, 90), img.getRGB(2, 0));
    }

    @Test
    void wrongDataSize_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> VideoFrameUtils.rgb24ToImage(new byte[5], 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> VideoFrameUtils.rgb24ToImage(new byte[13], 2, 2));
    }

    @Test
    void nullOrInvalidArguments_areRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> VideoFrameUtils.rgb24ToImage(null, 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> VideoFrameUtils.rgb24ToImage(new byte[12], 0, 2));
        assertThrows(IllegalArgumentException.class,
                () -> VideoFrameUtils.rgb24ToImage(new byte[12], 2, -1));
        assertThrows(IllegalArgumentException.class,
                () -> VideoFrameUtils.rgb24ToImage(new byte[12], -1, 2));
    }
}