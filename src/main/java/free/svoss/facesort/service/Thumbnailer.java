package free.svoss.facesort.service;

import free.svoss.facesort.util.ImageUtils;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * JPEG thumbnail encoding shared by the photo and video import services. The
 * quality used for every cached JPEG in the app (full-image thumbnails, video
 * frame thumbnails and face sub-images) comes from the configuration.
 */
final class Thumbnailer {

    private Thumbnailer() {
        // Utility class - not instantiable
    }

    /**
     * Downscales the image so its longest side is at most {@code maxDimension}
     * and encodes the result as JPEG. Database lookups and writes stay outside
     * this method: JPEG encoding must never run while holding the connection
     * monitor.
     *
     * @param image        the image to encode
     * @param maxDimension longest allowed side of the thumbnail
     * @param quality      JPEG quality in the range [0.1, 1.0]
     * @return the JPEG bytes
     * @throws IOException if the image cannot be encoded
     */
    static byte[] encode(BufferedImage image, int maxDimension, float quality) throws IOException {
        BufferedImage thumbnail = ImageUtils.downsize(image, maxDimension);
        return ImageUtils.toJpegBytes(thumbnail, quality);
    }
}