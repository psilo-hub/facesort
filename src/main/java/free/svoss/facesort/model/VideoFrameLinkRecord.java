package free.svoss.facesort.model;

import java.util.Objects;

/**
 * Represents a single extracted frame of a video.
 *
 * <p>The frame itself lives as an ordinary {@code images} row (its hash is the
 * SHA-256 of the frame's JPEG bytes). This record captures the
 * {@code video_frames} link: the video it came from plus the milliseconds
 * offset within that video at which the frame was sampled.
 */
public final class VideoFrameLinkRecord {

    private final String frameHash;
    private final String videoHash;
    private final long timestampMs;

    /**
     * Creates a link from database columns (used by {@code VideoDao}).
     *
     * @param frameHash   hash of the frame image (references {@code images.hash})
     * @param videoHash   hash of the video the frame was extracted from
     * @param timestampMs millisecond offset of the frame within the video
     */
    public VideoFrameLinkRecord(String frameHash, String videoHash, long timestampMs) {
        this.frameHash = Objects.requireNonNull(frameHash, "frameHash");
        this.videoHash = Objects.requireNonNull(videoHash, "videoHash");
        this.timestampMs = timestampMs;
    }

    /** Hash of the frame image (references {@code images.hash}). */
    public String frameHash() {
        return frameHash;
    }

    /** Hash of the video the frame belongs to. */
    public String videoHash() {
        return videoHash;
    }

    /** Millisecond offset of this frame within the video. */
    public long timestampMs() {
        return timestampMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof VideoFrameLinkRecord that
                && frameHash.equals(that.frameHash)
                && videoHash.equals(that.videoHash)
                && timestampMs == that.timestampMs;
    }

    @Override
    public int hashCode() {
        int result = frameHash.hashCode();
        result = 31 * result + videoHash.hashCode();
        result = 31 * result + (int) (timestampMs ^ (timestampMs >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "VideoFrameLinkRecord{frameHash='" + frameHash + "', videoHash='"
                + videoHash + "', timestampMs=" + timestampMs + '}';
    }
}
