package free.svoss.facesort.util;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Utility class for image processing operations.
 * Supports JPEG, PNG, BMP, GIF, and WebP (via TwelveMonkeys plugin).
 */
public final class ImageUtils {

    private ImageUtils() {
        // Utility class - not instantiable
    }

    /**
     * Downsizes an image so that neither dimension exceeds maxDimension,
     * preserving the aspect ratio. If the image is already small enough,
     * the original is returned unchanged.
     *
     * @param img          the source image
     * @param maxDimension the maximum width or height
     * @return the downsized image, or the original if no resizing needed
     */
    public static BufferedImage downsize(BufferedImage img, int maxDimension) {
        int width = img.getWidth();
        int height = img.getHeight();

        if (width <= maxDimension && height <= maxDimension) {
            return img;
        }

        double scale = Math.min((double) maxDimension / width, (double) maxDimension / height);
        int newWidth = (int) Math.round(width * scale);
        int newHeight = (int) Math.round(height * scale);

        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(img, 0, 0, newWidth, newHeight, null);
        } finally {
            g.dispose();
        }

        return resized;
    }

    /**
     * Encodes a BufferedImage as JPEG bytes with the specified quality.
     *
     * @param img     the image to encode
     * @param quality JPEG quality in range [0.0, 1.0] where 1.0 is maximum quality
     * @return the JPEG-encoded image bytes
     * @throws IOException if encoding fails
     */
    public static byte[] toJpegBytes(BufferedImage img, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }

        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(baos);
            writer.setOutput(output);
            writer.write(null, new IIOImage(img, null, null), param);
            output.flush();
            output.close();

            return baos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /**
     * Reads an image from the specified file path.
     * Supports JPEG, PNG, BMP, GIF, and WebP (via TwelveMonkeys plugin).
     *
     * @param file the path to the image file
     * @return the loaded BufferedImage
     * @throws IOException if the file cannot be read or is not a supported image format
     */
    public static BufferedImage readImage(Path file) throws IOException {
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) {
            throw new IOException("Unsupported image format or cannot read: " + file);
        }
        return img;
    }
}
