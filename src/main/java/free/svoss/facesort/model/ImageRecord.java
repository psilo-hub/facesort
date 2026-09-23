package free.svoss.facesort.model;

import java.util.Objects;

/**
 * Represents an imported image, uniquely identified by its SHA-256 content hash.
 *
 * <p>An image may be reachable through multiple file paths (duplicates on disk);
 * the paths are held by the {@code image_paths} table and resolved where needed.
 * The thumbnail is stored in the database as JPEG bytes.
 */
public final class ImageRecord {

    private final String hash;
    private final long detectionTs;
    private final String criteriaJson;
    private final int faceCount;

    /**
     * Creates a record from database columns (used by {@code ImageDao}).
     *
     * @param hash         content hash (SHA-256 hex)
     * @param detectionTs  epoch millis of the last face detection run, or 0
     * @param criteriaJson snapshot of the detection criteria used, or null
     * @param faceCount    number of faces that qualified for embedding
     */
    public ImageRecord(String hash, long detectionTs, String criteriaJson, int faceCount) {
        this.hash = Objects.requireNonNull(hash, "hash");
        this.detectionTs = detectionTs;
        this.criteriaJson = criteriaJson;
        this.faceCount = faceCount;
    }

    public String hash() {
        return hash;
    }

    /** Epoch millis of the last face detection run, or 0 when never detected. */
    public long detectionTs() {
        return detectionTs;
    }

    /** Snapshot of the detection criteria used, or null. */
    public String criteriaJson() {
        return criteriaJson;
    }

    /** Number of faces that qualified for embedding. */
    public int faceCount() {
        return faceCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ImageRecord that)) {
            return false;
        }
        return hash.equals(that.hash);
    }

    @Override
    public int hashCode() {
        return hash.hashCode();
    }

    @Override
    public String toString() {
        return "ImageRecord{hash='" + hash + "', faceCount=" + faceCount + '}';
    }
}