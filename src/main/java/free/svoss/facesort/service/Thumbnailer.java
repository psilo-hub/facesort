package free.svoss.facesort.service;

import free.svoss.facesort.util.ImageUtils;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * JPEG thumbnail encoding shared by the photo and video import services.
 * Offers the single quality constant used for every cached JPEG in the app
 * (full-image thumbnails, video frame thumbnails and face sub-images).
 */
final class Thumbnailer {

    /** JPEG quality for all stored thumbnails and face sub-images. */
    static final float JPEG_QUALITY = 0.85f;

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
     * @return the JPEG bytes
     * @throws IOException if the image cannot be encoded
     */
    static byte[] encode(BufferedImage image, int maxDimension) throws IOException {
        BufferedImage thumbnail = ImageUtils.downsize(image, maxDimension);
        return ImageUtils.toJpegBytes(thumbnail, JPEG_QUALITY);
    }
}