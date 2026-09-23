package free.svoss.facesort.model;

import java.util.Objects;

/**
 * Represents a detected face that qualified for embedding.
 *
 * <p>Maps to one row of the {@code faces} table. The bounding box is persisted
 * as integer coordinates ({@code bboxX/Y/W/H}).
 */
public final class FaceRecord {

    private final long id;
    private final String imageHash;
    private final int bboxX;
    private final int bboxY;
    private final int bboxW;
    private final int bboxH;
    private final double confidence;
    private final float[] embedding;
    private final byte[] subImageJpg;
    private final Long nameId;

    /**
     * Creates a face record from database columns (used by {@code FaceDao}).
     */
    public FaceRecord(long id, String imageHash, int bboxX, int bboxY, int bboxW, int bboxH,
                      double confidence, float[] embedding, byte[] subImageJpg, Long nameId) {
        this.id = id;
        this.imageHash = Objects.requireNonNull(imageHash, "imageHash");
        this.bboxX = bboxX;
        this.bboxY = bboxY;
        this.bboxW = bboxW;
        this.bboxH = bboxH;
        this.confidence = confidence;
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        this.subImageJpg = subImageJpg;
        this.nameId = nameId;
    }

    public long id() {
        return id;
    }

    /** Hash of the source image this face belongs to. */
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

    /**
     * Returns the internal embedding array. Callers must not modify it.
     */
    public float[] embedding() {
        return embedding;
    }

    /** JPEG bytes of the cropped face (max 160x160), or null when not available. */
    public byte[] subImageJpg() {
        return subImageJpg;
    }

    /** Name assigned to this face, or null when unnamed. */
    public Long nameId() {
        return nameId;
    }

    @Override
    public String toString() {
        return "FaceRecord{id=" + id + ", imageHash='" + imageHash + "', nameId=" + nameId + '}';
    }
}