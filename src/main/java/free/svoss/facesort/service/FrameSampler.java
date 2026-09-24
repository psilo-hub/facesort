package free.svoss.facesort.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-math planner for the video frame budget.
 *
 * <p>Only the sampling <em>targets</em> are computed here; decoding is the job
 * of a {@link VideoFrameSource}. The per-video frame cap is passed in by the
 * caller (from the configuration); the minimum spacing of one second is fixed
 * ({@link #MIN_FRAME_MS_SPACING}).</p>
 */
public final class FrameSampler {

    /** Minimum spacing between consecutive sampled frames, in milliseconds. */
    public static final int MIN_FRAME_MS_SPACING = 1000;

    private FrameSampler() {
        // Utility class - not instantiable
    }

    /**
     * Returns how many frames are sampled from a video of the given length:
     * one frame per second, capped at {@code maxFramesPerVideo}, and never
     * fewer than one frame.
     *
     * @param durationSecs       length of the video in seconds
     * @param maxFramesPerVideo  hard upper bound on the extracted frames
     * @return the number of frames to extract
     * @throws IllegalArgumentException if {@code durationSecs} is not positive
     *         or {@code maxFramesPerVideo} is not positive
     */
    public static int countFrames(double durationSecs, int maxFramesPerVideo) {
        if (durationSecs <= 0) {
            throw new IllegalArgumentException("durationSecs must be positive: " + durationSecs);
        }
        if (maxFramesPerVideo <= 0) {
            throw new IllegalArgumentException("maxFramesPerVideo must be positive: " + maxFramesPerVideo);
        }
        long durationMs = (long) (durationSecs * 1000);
        return (int) Math.min(maxFramesPerVideo,
                Math.max(1, durationMs / MIN_FRAME_MS_SPACING));
    }

    /**
     * Returns the sampling targets for a video of the given length, as seconds.
     *
     * <p>The targets are evenly spaced in time: {@code count} is given by
     * {@link #countFrames}, the spacing is {@code durationSecs / count} and the
     * {@code i}-th target is {@code (i + 0.5) * spacing}. Every target lies in
     * {@code [0, durationSecs)} and, for videos of at least one second,
     * consecutive targets are at least one second apart.</p>
     *
     * @param durationSecs       length of the video in seconds
     * @param maxFramesPerVideo  hard upper bound on the extracted frames
     * @return the ascending list of sample targets in seconds
     * @throws IllegalArgumentException if {@code durationSecs} is not positive
     *         or {@code maxFramesPerVideo} is not positive
     */
    public static List<Double> sampleTargets(double durationSecs, int maxFramesPerVideo) {
        int count = countFrames(durationSecs, maxFramesPerVideo);
        double spacing = durationSecs / count;
        List<Double> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            targets.add((i + 0.5) * spacing);
        }
        return targets;
    }
}