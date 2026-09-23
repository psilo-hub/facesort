package free.svoss.facesort.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-math planner for the video frame budget.
 *
 * <p>Only the sampling <em>targets</em> are computed here; decoding is the job
 * of a {@link VideoFrameSource}. The budget is hard-coded for now
 * ({@link #MAX_FRAMES_PER_VIDEO} frames, at most one per second); exposing it
 * in the Settings tab is future work.</p>
 */
public final class FrameSampler {

    /** Hard upper bound on the number of frames extracted per video. */
    public static final int MAX_FRAMES_PER_VIDEO = 120;

    /** Minimum spacing between consecutive sampled frames, in milliseconds. */
    public static final int MIN_FRAME_MS_SPACING = 1000;

    private FrameSampler() {
        // Utility class - not instantiable
    }

    /**
     * Returns how many frames are sampled from a video of the given length:
     * one frame per second, capped at {@link #MAX_FRAMES_PER_VIDEO}, and never
     * fewer than one frame.
     *
     * @param durationSecs length of the video in seconds
     * @return the number of frames to extract
     * @throws IllegalArgumentException if {@code durationSecs} is not positive
     */
    public static int countFrames(double durationSecs) {
        if (durationSecs <= 0) {
            throw new IllegalArgumentException("durationSecs must be positive: " + durationSecs);
        }
        long durationMs = (long) (durationSecs * 1000);
        return (int) Math.min(MAX_FRAMES_PER_VIDEO,
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
     * @param durationSecs length of the video in seconds
     * @return the ascending list of sample targets in seconds
     * @throws IllegalArgumentException if {@code durationSecs} is not positive
     */
    public static List<Double> sampleTargets(double durationSecs) {
        int count = countFrames(durationSecs);
        double spacing = durationSecs / count;
        List<Double> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            targets.add((i + 0.5) * spacing);
        }
        return targets;
    }
}