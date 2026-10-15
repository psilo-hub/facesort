package free.svoss.facesort.service;

import free.svoss.tools.faceai.DetectedFace;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link FaceRotation}: the quarter-turn offset read off the face
 * landmarks and the exact, resampling-free rotation of the crop.
 *
 * <p>The pixel tests use a 3x2 image with six distinct colors, so a rotation
 * that lands one pixel off — or that runs in the wrong direction — fails
 * instead of accidentally looking right.</p>
 */
class FaceRotationTest {

    private static final int A = 0x010203;
    private static final int B = 0x040506;
    private static final int C = 0x070809;
    private static final int D = 0x0A0B0C;
    private static final int E = 0x0D0E0F;
    private static final int F = 0x101112;

    /** A face standing upright in screen coordinates: the mouth sits below the eyes. */
    private static final float[] UPRIGHT_LANDMARKS = {
            40, 30,   // left eye
            60, 30,   // right eye
            50, 45,   // nose tip
            42, 60,   // left mouth corner
            58, 60};  // right mouth corner

    @Test
    void uprightQuarterTurns_isZeroForAnUprightFace() {
        assertEquals(0, FaceRotation.uprightQuarterTurns(face(UPRIGHT_LANDMARKS)));
    }

    @Test
    void uprightQuarterTurns_isZeroWithoutLandmarks() {
        assertEquals(0, FaceRotation.uprightQuarterTurns(new DetectedFace(0, 0, 10, 10, 0.9f)),
                "a detection without landmarks carries no rotation information");
    }

    @Test
    void uprightQuarterTurns_leavesASlightTiltAlone() {
        assertEquals(0, FaceRotation.uprightQuarterTurns(face(rotate(UPRIGHT_LANDMARKS, 15))),
                "a tilt well below 45 degrees must not snap to a quarter turn");
        assertEquals(0, FaceRotation.uprightQuarterTurns(face(rotate(UPRIGHT_LANDMARKS, -30))),
                "a tilt well below 45 degrees must not snap to a quarter turn");
    }

    @Test
    void uprightQuarterTurns_undoesEveryQuarterTurnTheFaceWasStoredRotatedBy() {
        for (int stored = 0; stored < 4; stored++) {
            int expected = (4 - stored) % 4;
            assertEquals(expected,
                    FaceRotation.uprightQuarterTurns(face(rotate(UPRIGHT_LANDMARKS, stored * 90.0))),
                    "a face stored rotated " + stored + " quarter turn(s) clockwise must be "
                            + "turned back by " + expected);
        }
    }

    @Test
    void uprightQuarterTurns_treatsAnUpsideDownFaceAsHalfATurn() {
        assertEquals(2, FaceRotation.uprightQuarterTurns(face(rotate(UPRIGHT_LANDMARKS, 180.0))),
                "the eyes-to-mouth vector keeps the 180 degree case apart from 0 degrees");
    }

    @Test
    void rotateQuarterTurns_returnsTheSameImageWhenThereIsNothingToDo() {
        BufferedImage image = pixels();
        assertSame(image, FaceRotation.rotateQuarterTurns(image, 0));
        assertSame(image, FaceRotation.rotateQuarterTurns(image, 4));
        assertSame(image, FaceRotation.rotateQuarterTurns(image, -4));
    }

    @Test
    void rotateQuarterTurns_placesEveryPixelExactlyWhereAClockwiseRotationPutsIt() {
        assertArrayEquals(new int[]{D, A, E, B, F, C},
                rgbOf(FaceRotation.rotateQuarterTurns(pixels(), 1)),
                "one clockwise quarter turn maps a 3x2 image onto a 2x3 image "
                        + "with (x, y) -> (1 - y, x)");
        assertArrayEquals(new int[]{F, E, D, C, B, A},
                rgbOf(FaceRotation.rotateQuarterTurns(pixels(), 2)),
                "two quarter turns mirror both axes, keeping the image 3x2");
        assertArrayEquals(new int[]{C, F, B, E, A, D},
                rgbOf(FaceRotation.rotateQuarterTurns(pixels(), 3)),
                "three clockwise quarter turns are one counter-clockwise quarter turn");
    }

    @Test
    void rotateQuarterTurns_doesNotResample() {
        BufferedImage rotated = FaceRotation.rotateQuarterTurns(pixels(), 1);
        List<Integer> sourceColors = List.of(A, B, C, D, E, F);

        assertEquals(2, rotated.getWidth());
        assertEquals(3, rotated.getHeight());
        for (int rgb : rgbOf(rotated)) {
            assertTrue(sourceColors.contains(rgb),
                    "an interpolated pixel would produce a color the source does not contain");
        }
    }

    @Test
    void uprightCrop_leavesAnUprightFaceExactlyAsCropped() {
        BufferedImage source = gradient(40, 40);
        DetectedFace face = new DetectedFace(4, 6, 20, 24, 0.9f, UPRIGHT_LANDMARKS);

        BufferedImage upright = FaceRotation.uprightCrop(face, source);

        assertArrayEquals(rgbOf(face.crop(source)), rgbOf(upright),
                "an upright face must be stored exactly as it was cropped");
    }

    @Test
    void uprightCrop_straightensASidewaysFace() {
        BufferedImage source = gradient(40, 40);
        // Eyes to the right of the mouth: the face was recorded rotated a
        // quarter turn clockwise and must be turned back.
        DetectedFace sideways = new DetectedFace(4, 6, 20, 30, 0.9f, new float[]{
                14, 12,   // left eye
                14, 18,   // right eye
                9, 15,    // nose tip
                4, 13,    // left mouth corner
                4, 17});  // right mouth corner

        BufferedImage upright = FaceRotation.uprightCrop(sideways, source);

        assertEquals(30, upright.getWidth(), "a sideways face crop is stored with its sides swapped");
        assertEquals(20, upright.getHeight(), "a sideways face crop is stored with its sides swapped");
        BufferedImage crop = sideways.crop(source);
        assertEquals(crop.getRGB(0, 0) & 0xFFFFFF, upright.getRGB(0, 19) & 0xFFFFFF,
                "the crop's top-left corner must land in the upright crop's bottom-left corner");
        assertEquals(crop.getRGB(19, 29) & 0xFFFFFF, upright.getRGB(29, 0) & 0xFFFFFF,
                "the crop's bottom-right corner must land in the upright crop's top-right corner");
    }

    private static DetectedFace face(float[] landmarks) {
        return new DetectedFace(0, 0, 100, 100, 0.9f, landmarks);
    }

    /** Rotates landmark pairs clockwise on screen by the given angle. */
    private static float[] rotate(float[] landmarks, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        float[] rotated = new float[landmarks.length];
        for (int i = 0; i < landmarks.length; i += 2) {
            rotated[i] = (float) (landmarks[i] * cos - landmarks[i + 1] * sin);
            rotated[i + 1] = (float) (landmarks[i] * sin + landmarks[i + 1] * cos);
        }
        return rotated;
    }

    /** The 3x2 image {@code A B C} over {@code D E F}. */
    private static BufferedImage pixels() {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, A);
        image.setRGB(1, 0, B);
        image.setRGB(2, 0, C);
        image.setRGB(0, 1, D);
        image.setRGB(1, 1, E);
        image.setRGB(2, 1, F);
        return image;
    }

    /** A non-uniform image, so a misplaced pixel is visible. */
    private static BufferedImage gradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (x * 31 + y * 7) & 0xFFFFFF);
            }
        }
        return image;
    }

    /** Row-major RGB values with the alpha channel stripped. */
    private static int[] rgbOf(BufferedImage image) {
        int[] rgb = new int[image.getWidth() * image.getHeight()];
        int index = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                rgb[index++] = image.getRGB(x, y) & 0xFFFFFF;
            }
        }
        return rgb;
    }
}
