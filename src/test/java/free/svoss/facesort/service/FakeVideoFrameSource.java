package free.svoss.facesort.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * In-memory {@link VideoFrameSource} for service tests. Mirrors the contract of
 * the real {@code FfmpegVideoFrameSource}: a configurable duration, a list of
 * frame positions that are served in ascending order, forward-only seeking
 * (rewinding throws {@link IllegalStateException}) and a graceful end of stream
 * ({@code null}) once no more frames exist.
 *
 * <p>Each frame is a small solid-color image whose shade is derived from the
 * frame's position, so tests can verify which frame was actually delivered.</p>
 */
final class FakeVideoFrameSource implements VideoFrameSource {

    /** Delivered frame color: gray level derived from the frame position. */
    static Color frameColor(double positionSeconds) {
        int gray = (int) Math.round((positionSeconds * 97) % 256);
        return new Color(gray, gray, gray);
    }

    private final double duration;
    private final double[] framePositions;

    private int nextFrame;
    private double lastTarget = -1.0;
    private boolean closed;

    /**
     * @param duration        the duration reported by {@link #getDuration()}
     * @param framePositions  ascending positions (seconds) of the frames this
     *                        source can serve
     */
    FakeVideoFrameSource(double duration, double... framePositions) {
        if (duration <= 0) {
            throw new IllegalArgumentException("duration must be positive");
        }
        if (framePositions == null) {
            throw new IllegalArgumentException("framePositions must not be null");
        }
        this.duration = duration;
        this.framePositions = framePositions.clone();
    }

    @Override
    public double getDuration() {
        return duration;
    }

    @Override
    public SampledFrame seekTo(double targetSeconds) {
        if (closed) {
            throw new IllegalStateException("source is already closed");
        }
        if (targetSeconds < lastTarget) {
            throw new IllegalStateException("seek must be forward-only: target "
                    + targetSeconds + " is behind " + lastTarget);
        }
        lastTarget = targetSeconds;

        // Skip frames strictly before the target, then serve the first frame
        // at or after it (mirrors ffmpeg seeking, which lands on a nearby
        // frame that can differ from the exact requested position).
        while (nextFrame < framePositions.length
                && framePositions[nextFrame] < targetSeconds) {
            nextFrame++;
        }
        if (nextFrame >= framePositions.length) {
            return null;
        }
        double position = framePositions[nextFrame];
        nextFrame++;

        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(frameColor(position));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.dispose();
        return new SampledFrame(image, position);
    }

    @Override
    public void close() {
        closed = true;
    }
}