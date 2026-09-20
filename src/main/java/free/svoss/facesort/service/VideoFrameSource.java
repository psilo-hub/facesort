package free.svoss.facesort.service;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Test seam for reading sampled frames out of a video file.
 *
 * <p>Mirrors the {@code FaceAiService.Engine} seam: the video import service
 * consumes this interface so its behavior can be tested against a fake even
 * though the real implementation ({@code FfmpegVideoFrameSource}) wraps the
 * ffmpeg4j native library.</p>
 *
 * <p>Seeking is strictly <em>forward-only</em>: targets must never be lower
 * than the previous seek; rewinding throws {@link IllegalStateException}.</p>
 */
public interface VideoFrameSource extends AutoCloseable {

    /**
     * Returns the duration of the video in seconds, probed when the source was
     * opened.
     *
     * @return the duration in seconds, always positive
     */
    double getDuration();

    /**
     * Moves forward to the given target position and decodes a frame there.
     *
     * <p>Seeking is forward-only: passing a target behind the previous one
     * throws {@link IllegalStateException}. When the stream runs out of frames
     * before reaching the target, {@code null} is returned so the caller can
     * stop extraction gracefully.</p>
     *
     * @param targetSeconds the position to seek to, in seconds
     * @return the decoded frame including its real position in the video, or
     *         {@code null} when the stream ended before the target
     * @throws IOException             if the stream cannot be read or decoded
     * @throws IllegalStateException   if the target is behind the current position
     */
    SampledFrame seekTo(double targetSeconds) throws IOException;

    @Override
    void close();

    /**
     * A decoded video frame: the image itself plus the position in the video
     * that the frame was actually taken from (which can differ slightly from
     * the requested seek target).
     *
     * @param image          the decoded frame image
     * @param positionSeconds the real position of the frame in the video, in seconds
     */
    record SampledFrame(BufferedImage image, double positionSeconds) {
    }
}