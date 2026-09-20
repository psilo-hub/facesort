package free.svoss.facesort.model;

import java.util.Objects;

/**
 * Represents an imported video, uniquely identified by its SHA-256 content hash.
 *
 * <p>A video may be reachable through multiple file paths (duplicates on disk).
 * Extracted frames are stored as ordinary image rows referenced from
 * {@code video_frames}; {@code frameCount} counts those links and
 * {@code faceCount} the faces detected across them.
 */
public final class VideoRecord {

    private final String hash;
    private final long detectionTs;
    private final String criteriaJson;
    private final double durationSecs;
    private final int frameCount;
    private final int faceCount;

    /**
     * Creates a record from database columns (used by {@code VideoDao}).
     *
     * @param hash         content hash (SHA-256 hex)
     * @param detectionTs  epoch millis of the last face detection run, or 0
     * @param criteriaJson snapshot of the detection criteria used, or null
     * @param durationSecs video length in seconds
     * @param frameCount   number of frames linked to this video
     * @param faceCount    number of faces detected across its frames
     */
    public VideoRecord(String hash, long detectionTs, String criteriaJson,
                       double durationSecs, int frameCount, int faceCount) {
        this.hash = Objects.requireNonNull(hash, "hash");
        this.detectionTs = detectionTs;
        this.criteriaJson = criteriaJson;
        this.durationSecs = durationSecs;
        this.frameCount = frameCount;
        this.faceCount = faceCount;
    }

    /** SHA-256 content hash. */
    public String hash() {
        return hash;
    }

    /** Epoch millis of the last face detection run, or 0 when never run. */
    public long detectionTs() {
        return detectionTs;
    }

    /** Snapshot of the detection criteria used, or null. */
    public String criteriaJson() {
        return criteriaJson;
    }

    /** Video length in seconds. */
    public double durationSecs() {
        return durationSecs;
    }

    /** Number of frames currently linked to this video. */
    public int frameCount() {
        return frameCount;
    }

    /** Number of faces detected across the linked frames. */
    public int faceCount() {
        return faceCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VideoRecord that)) {
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
        return "VideoRecord{hash='" + hash + "', durationSecs=" + durationSecs
                + ", frameCount=" + frameCount + ", faceCount=" + faceCount + '}';
    }
}
