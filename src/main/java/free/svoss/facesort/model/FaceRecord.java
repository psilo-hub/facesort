package free.svoss.facesort.model;

import javafx.geometry.Rectangle2D;

import java.util.Objects;

/**
 * Represents a detected face that qualified for embedding.
 *
 * <p>Maps to one row of the {@code faces} table. The bounding box is persisted
 * as integer coordinates; {@link #boundingBox()} exposes it as a
 * {@link Rectangle2D} for UI work. {@code faceIndex} is the 0-based position of
 * the face within its source image, or -1 when unknown.
 */
public final class FaceRecord {

    private final long id;
    private final String imageHash;
    private final int faceIndex;
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
     * {@code faceIndex} is unknown in this case and set to -1.
     */
    public FaceRecord(long id, String imageHash, int bboxX, int bboxY, int bboxW, int bboxH,
                      double confidence, float[] embedding, byte[] subImageJpg, Long nameId) {
        this(id, imageHash, -1, bboxX, bboxY, bboxW, bboxH, confidence, embedding, subImageJpg, nameId);
    }

    /**
     * Creates a face record with an explicit face index.
     */
    public FaceRecord(long id, String imageHash, int faceIndex, int bboxX, int bboxY,
                      int bboxW, int bboxH, double confidence, float[] embedding,
                      byte[] subImageJpg, Long nameId) {
        this.id = id;
        this.imageHash = Objects.requireNonNull(imageHash, "imageHash");
        this.faceIndex = faceIndex;
        this.bboxX = bboxX;
        this.bboxY = bboxY;
        this.bboxW = bboxW;
        this.bboxH = bboxH;
        this.confidence = confidence;
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        this.subImageJpg = subImageJpg;
        this.nameId = nameId;
    }

    /**
     * Convenience constructor for UI code that works with a {@link Rectangle2D}
     * bounding box. Confidence is set to 0 and the sub-image to null.
     *
     * <p><b>Persistence constraint:</b> the {@code faces} table declares
     * {@code sub_image_jpg BLOB NOT NULL}. Records built with this constructor
     * carry no sub-image bytes and therefore must only be used for display.
     * They must never be passed to {@code FaceDao.insert(FaceRecord)}, which
     * requires real JPEG bytes (rejecting null with a clear SQLException).
     */
    public FaceRecord(long id, String imageHash, int faceIndex, Rectangle2D bounds,
                      float[] embedding, Long nameId) {
        this(id, imageHash, faceIndex,
                (int) bounds.getMinX(), (int) bounds.getMinY(),
                (int) bounds.getWidth(), (int) bounds.getHeight(),
                0.0, embedding, null, nameId);
    }

    public long id() {
        return id;
    }

    /** Hash of the source image this face belongs to. */
    public String imageHash() {
        return imageHash;
    }

    /** 0-based position within the source image, or -1 when unknown. */
    public int faceIndex() {
        return faceIndex;
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

    /** The face's bounding box as a JavaFX rectangle. */
    public Rectangle2D boundingBox() {
        return new Rectangle2D(bboxX, bboxY, bboxW, bboxH);
    }

    @Override
    public String toString() {
        return "FaceRecord{id=" + id + ", imageHash='" + imageHash + "', nameId=" + nameId + '}';
    }
}