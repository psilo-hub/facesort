package free.svoss.facesort.util;

import java.awt.image.BufferedImage;

/**
 * Utility class for converting raw video frame data into images.
 *
 * <p>ffmpeg frame decoders deliver packed <em>RGB24</em> bytes: row-major,
 * three bytes per pixel (red, green, blue). This class converts that layout to
 * a {@link BufferedImage} for the rest of the pipeline (thumbnails, hashes,
 * face detection).</p>
 */
public final class VideoFrameUtils {

    private VideoFrameUtils() {
        // Utility class - not instantiable
    }

    /**
     * Converts packed RGB24 frame bytes into a {@code TYPE_INT_RGB} image.
     *
     * @param data   packed RGB24 bytes, exactly {@code width * height * 3} long
     * @param width  image width in pixels; must be positive
     * @param height image height in pixels; must be positive
     * @return the decoded image
     * @throws IllegalArgumentException if {@code data} is {@code null}, the
     *                                  dimensions are not positive, or the data
     *                                  length does not match the dimensions
     */
    public static BufferedImage rgb24ToImage(byte[] data, int width, int height) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive: " + width + "x" + height);
        }
        int expected = width * height * 3;
        if (data.length != expected) {
            throw new IllegalArgumentException("data length " + data.length
                    + " does not match " + width + "x" + height + " RGB24 (" + expected + " bytes)");
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = new int[width * height];
        for (int i = 0, p = 0; i < pixels.length; i++, p += 3) {
            int r = data[p] & 0xFF;
            int g = data[p + 1] & 0xFF;
            int b = data[p + 2] & 0xFF;
            pixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }
}