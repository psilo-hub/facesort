package free.svoss.facesort.service;

import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.util.ImageUtils;
import free.svoss.tools.faceai.DetectedFace;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared face-detection pipeline used by both {@link ImportService} and
 * {@link VideoImportService}, so photos and video frames are analysed with
 * identical criteria.
 *
 * <p>Large images are scaled down to {@code maxDetectionDimension} before the
 * neural network runs; the resulting bounding boxes are mapped back to the
 * original image coordinates, filtered by the configured criteria, and every
 * qualifying face is cropped at full resolution but immediately downscaled so
 * that neither embedding inference nor JPEG encoding ever processes a large
 * crop.</p>
 */
final class FaceDetectionUtils {

    private static final Logger LOG = Logger.getLogger(FaceDetectionUtils.class.getName());

    /** Maximum dimension (width or height) for face sub-image thumbnails. */
    private static final int SUB_IMAGE_MAX_DIM = 160;

    /** JPEG quality for face sub-image encoding. */
    private static final float SUB_IMAGE_JPEG_QUALITY = 0.85f;

    private FaceDetectionUtils() {
        // Utility class - not instantiable
    }

    /**
     * Detects, filters and embeds every qualifying face in the given image,
     * returning the {@link FaceRecord}s ready for persistence.
     *
     * @param imageHash              hash the face records must reference
     * @param image                  the full-resolution image
     * @param maxDetectionDimension  longest allowed side of the detection input
     * @param minBbox                minimum qualifying bounding box size (px)
     * @param minConfidence          minimum qualifying confidence
     * @param maxFacesPerImage       maximum faces kept per image
     * @param service                face detection and embedding service
     * @param sourceDescription      human-readable source for error logs
     * @return the qualifying faces, each with its stored sub-image JPEG
     */
    static List<FaceRecord> detectFaces(String imageHash, BufferedImage image,
                                        int maxDetectionDimension, int minBbox,
                                        double minConfidence, int maxFacesPerImage,
                                        FaceAiService service, String sourceDescription) {
        double detectionScale = computeDetectionScale(image, maxDetectionDimension);
        BufferedImage detectionImage = detectionScale < 1.0
                ? ImageUtils.downsize(image, maxDetectionDimension)
                : image;
        DetectedFace[] allFaces = service.detectFaces(detectionImage);

        // Filter faces by criteria, working in original image coordinates
        List<DetectedFace> qualifying = Arrays.stream(allFaces)
                .map(face -> mapToOriginal(face, detectionScale))
                .filter(f -> f.width() >= minBbox && f.height() >= minBbox)
                .filter(f -> f.confidence() >= minConfidence)
                .limit(maxFacesPerImage)
                .toList();

        // Encode face sub-images before touching the database. Each face is
        // cropped at full resolution but immediately downscaled so that neither
        // embedding inference nor JPEG encoding ever processes a large crop.
        List<FaceRecord> faceRecords = new ArrayList<>();
        for (DetectedFace face : qualifying) {
            try {
                BufferedImage faceCrop = face.crop(image);
                BufferedImage faceThumb = ImageUtils.downsize(faceCrop, SUB_IMAGE_MAX_DIM);
                float[] embedding = service.getEmbedding(faceThumb);
                byte[] subImageJpg = ImageUtils.toJpegBytes(faceThumb, SUB_IMAGE_JPEG_QUALITY);

                faceRecords.add(new FaceRecord(
                        0, imageHash,
                        face.x(), face.y(), face.width(), face.height(),
                        face.confidence(), embedding, subImageJpg, null));
            } catch (Exception e) {
                LOG.log(Level.WARNING,
                        "Error processing detected face in " + sourceDescription, e);
            }
        }
        return faceRecords;
    }

    /**
     * Builds a compact JSON string recording the detection criteria used for
     * an import batch.
     */
    static String buildCriteriaJson(int minBbox, double minConfidence, int maxFaces) {
        return String.format(Locale.ROOT,
                "{\"minBbox\":%d,\"minConfidence\":%.2f,\"maxFacesPerImage\":%d}",
                minBbox, minConfidence, maxFaces);
    }

    /**
     * Computes the factor used to scale an image down for face detection.
     * Returns {@code 1.0} when the image already fits within
     * {@code maxDimension}.
     *
     * @param image        the full-resolution image
     * @param maxDimension longest allowed side of the detection input
     * @return the scale applied to the image ({@code 0 < scale <= 1})
     */
    private static double computeDetectionScale(BufferedImage image, int maxDimension) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= maxDimension && height <= maxDimension) {
            return 1.0;
        }
        return Math.min((double) maxDimension / width, (double) maxDimension / height);
    }

    /**
     * Maps a face detected on a scaled-down image back to the original image
     * coordinates. When {@code scale} is {@code 1.0} the face is returned
     * unchanged. Coordinates are rounded the same way {@link ImageUtils#crop}
     * rounds them.
     *
     * @param face  the face detected on the scaled image
     * @param scale the scale the image was downscaled by before detection
     * @return an equivalent face in original image coordinates
     */
    private static DetectedFace mapToOriginal(DetectedFace face, double scale) {
        if (scale >= 1.0) {
            return face;
        }
        return new DetectedFace(
                (int) Math.round(face.x() / scale),
                (int) Math.round(face.y() / scale),
                (int) Math.round(face.width() / scale),
                (int) Math.round(face.height() / scale),
                face.confidence());
    }
}