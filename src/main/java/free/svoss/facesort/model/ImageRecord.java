package free.svoss.facesort.model;

import java.util.List;
import java.util.Objects;

/**
 * Represents an imported image, uniquely identified by its SHA-256 content hash.
 *
 * <p>An image may be reachable through multiple file paths (duplicates on disk).
 * The thumbnail is normally stored in the database as JPEG bytes;
 * {@code thumbnailPath} carries an on-disk location when one has been
 * materialized, or is {@code null} otherwise.
 */
public final class ImageRecord {

    private final String hash;
    private final long detectionTs;
    private final String criteriaJson;
    private final List<String> paths;
    private final String thumbnailPath;
    private final boolean hasFaces;

    /**
     * Creates a record from database columns (used by {@code ImageDao}).
     *
     * @param hash         content hash (SHA-256 hex)
     * @param detectionTs  epoch millis of the last face detection run, or 0
     * @param criteriaJson snapshot of the detection criteria used, or null
     * @param faceCount    number of faces that qualified for embedding
     */
    public ImageRecord(String hash, long detectionTs, String criteriaJson, int faceCount) {
        this(hash, detectionTs, criteriaJson, List.of(), null, faceCount > 0);
    }

    /**
     * Creates a fully populated record, typically for UI-facing use.
     *
     * @param hash          content hash (SHA-256 hex)
     * @param paths         all known on-disk paths for this image
     * @param thumbnailPath on-disk thumbnail location, or null
     * @param hasFaces      true when at least one face was detected
     */
    public ImageRecord(String hash, List<String> paths, String thumbnailPath, boolean hasFaces) {
        this(hash, 0L, null, paths, thumbnailPath, hasFaces);
    }

    private ImageRecord(String hash, long detectionTs, String criteriaJson,
                        List<String> paths, String thumbnailPath, boolean hasFaces) {
        this.hash = Objects.requireNonNull(hash, "hash");
        this.detectionTs = detectionTs;
        this.criteriaJson = criteriaJson;
        this.paths = List.copyOf(paths);
        this.thumbnailPath = thumbnailPath;
        this.hasFaces = hasFaces;
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

    /** All known on-disk paths for this image. */
    public List<String> paths() {
        return paths;
    }

    /** On-disk thumbnail location, or null. */
    public String thumbnailPath() {
        return thumbnailPath;
    }

    /** True when at least one face was detected for this image. */
    public boolean hasFaces() {
        return hasFaces;
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
        return "ImageRecord{hash='" + hash + "', paths=" + paths + ", hasFaces=" + hasFaces + '}';
    }
}