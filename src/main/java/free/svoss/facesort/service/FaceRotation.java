package free.svoss.facesort.service;

import free.svoss.tools.faceai.DetectedFace;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * Works out how far a detected face is rotated and turns a crop back upright.
 *
 * <p>{@link DetectedFace} landmarks follow the RetinaFace five-point convention
 * (left eye, right eye, nose tip, left mouth corner, right mouth corner). The
 * eye line on its own only ever reveals a rotation folded into
 * {@code [-90, 90]}, so an upside-down face looks perfectly upright to it. The
 * eyes-to-mouth vector does not fold: on an upright face it points straight
 * down, which makes the full quarter-turn offset unambiguous.</p>
 *
 * <p>The correction snaps to the nearest quarter turn. A sideways or
 * upside-down crop is put back on its feet with an exact, resampling-free
 * rotation, while a normal portrait — including one that is only slightly
 * tilted — is left exactly as it is.</p>
 */
final class FaceRotation {

    private FaceRotation() {
        // Utility class - not instantiable
    }

    /**
     * Returns how many quarter turns a face crop has to be rotated by
     * (clockwise) to stand upright.
     *
     * @param face the detected face; must not be null
     * @return a value in {@code [0, 3]}; {@code 0} when the face already is
     *         upright or carries no landmarks to tell
     */
    static int uprightQuarterTurns(DetectedFace face) {
        Objects.requireNonNull(face, "face must not be null");
        if (!face.hasLandmarks()) {
            return 0;
        }
        float[] landmarks = face.landmarks();
        double eyesMidX = (landmarks[0] + landmarks[2]) / 2.0;
        double eyesMidY = (landmarks[1] + landmarks[3]) / 2.0;
        double mouthMidX = (landmarks[6] + landmarks[8]) / 2.0;
        double mouthMidY = (landmarks[7] + landmarks[9]) / 2.0;
        double dx = mouthMidX - eyesMidX;
        double dy = mouthMidY - eyesMidY;
        if (dx == 0.0 && dy == 0.0) {
            return 0;
        }
        // Screen y grows downwards, so an upright face has the eyes-to-mouth
        // vector pointing at +90 degrees; rotating the image clockwise by the
        // difference puts it back there.
        double facingDownDegrees = Math.toDegrees(Math.atan2(dy, dx));
        double correctionDegrees = 90.0 - facingDownDegrees;
        return Math.floorMod((int) Math.round(correctionDegrees / 90.0), 4);
    }

    /**
     * Rotates an image by a whole number of quarter turns, clockwise.
     *
     * <p>The rotation is axis-aligned, so it is exact: no interpolation and no
     * resampling blur. {@code 0} returns the given image unchanged.</p>
     *
     * @param image        the image to rotate; must not be null
     * @param quarterTurns the number of clockwise quarter turns
     * @return the rotated image, or the given image when there is nothing to do
     */
    static BufferedImage rotateQuarterTurns(BufferedImage image, int quarterTurns) {
        Objects.requireNonNull(image, "image must not be null");
        int turns = Math.floorMod(quarterTurns, 4);
        if (turns == 0) {
            return image;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        boolean halfTurn = turns == 2;
        int rotatedWidth = halfTurn ? width : height;
        int rotatedHeight = halfTurn ? height : width;
        BufferedImage rotated =
                new BufferedImage(rotatedWidth, rotatedHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rotated.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            // Rotate around the shared centre so pixel centres map exactly onto
            // pixel centres and no interpolation is needed.
            g.translate(rotatedWidth / 2.0, rotatedHeight / 2.0);
            g.rotate(Math.toRadians(90.0 * turns));
            g.translate(-width / 2.0, -height / 2.0);
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rotated;
    }

    /**
     * Crops a face from its source image and rotates the crop upright in one
     * step.
     *
     * @param face   the detected face; must not be null
     * @param source the original image the face was detected in; must not be null
     * @return the upright face crop
     */
    static BufferedImage uprightCrop(DetectedFace face, BufferedImage source) {
        return rotateQuarterTurns(face.crop(source), uprightQuarterTurns(face));
    }
}
