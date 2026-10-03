package free.svoss.facesort.model;

import java.util.Objects;

/**
 * A lightweight projection of a detected face without the embedding vector,
 * used for display in views where similarity calculations are not needed.
 */
public final class FaceThumb {

    private final long id;
    private final String imageHash;
    private final int bboxX;
    private final int bboxY;
    private final int bboxW;
    private final int bboxH;
    private final double confidence;
    private final byte[] subImageJpg;
    private final Long nameId;

    public FaceThumb(long id, String imageHash, int bboxX, int bboxY, int bboxW, int bboxH,
                     double confidence, byte[] subImageJpg, Long nameId) {
        this.id = id;
        this.imageHash = Objects.requireNonNull(imageHash, "imageHash");
        this.bboxX = bboxX;
        this.bboxY = bboxY;
        this.bboxW = bboxW;
        this.bboxH = bboxH;
        this.confidence = confidence;
        this.subImageJpg = subImageJpg;
        this.nameId = nameId;
    }

    public long id() {
        return id;
    }

    public String imageHash() {
        return imageHash;
    }

    public int bboxX() {
        return bboxX;
    }

    public int bboxY() {
        return bboxY;
    }

    public int bboxW() {
        return bboxW;
    }

    public int bboxH() {
        return bboxH;
    }

    public double confidence() {
        return confidence;
    }

    public byte[] subImageJpg() {
        return subImageJpg;
    }

    public Long nameId() {
        return nameId;
    }
}
