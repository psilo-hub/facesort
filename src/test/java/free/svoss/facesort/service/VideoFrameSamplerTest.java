package free.svoss.facesort.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure-math {@link FrameSampler}: the frame budget
 * (at most one frame per second, at most {@code MAX_FRAMES_PER_VIDEO} per
 * video, never zero) and the evenly spaced sampling targets.
 */
class VideoFrameSamplerTest {

    @Test
    void constants_areThePlannedLimits() {
        assertEquals(120, FrameSampler.MAX_FRAMES_PER_VIDEO);
        assertEquals(1000, FrameSampler.MIN_FRAME_MS_SPACING);
    }

    @Test
    void shortVideos_yieldOneFrameWithinDuration() {
        for (double duration : new double[]{0.1, 0.5, 0.99}) {
            List<Double> targets = FrameSampler.sampleTargets(duration);
            assertEquals(1, targets.size(), "duration " + duration + "s must yield one frame");
            double target = targets.get(0);
            assertTrue(target >= 0 && target < duration,
                    "target " + target + " must be in [0, " + duration + ")");
        }
    }

    @Test
    void oneSecondVideos_yieldOneFrame() {
        List<Double> targets = FrameSampler.sampleTargets(1.0);
        assertEquals(1, targets.size());
        assertEquals(0.5, targets.get(0), 0.0001);
    }

    @Test
    void oneToHundredTwentySeconds_yieldOneFramePerSecond() {
        assertEquals(2, FrameSampler.countFrames(2.0));
        assertEquals(5, FrameSampler.countFrames(5.0));
        assertEquals(120, FrameSampler.countFrames(120.0));
        // Non-integer durations round down to whole seconds.
        assertEquals(5, FrameSampler.countFrames(5.5));
        assertEquals(119, FrameSampler.countFrames(119.7));
    }

    @Test
    void overHundredTwentySeconds_yieldTheCap() {
        assertEquals(120, FrameSampler.countFrames(121.0));
        assertEquals(120, FrameSampler.countFrames(240.0));
        assertEquals(120, FrameSampler.countFrames(3600.0));
    }

    @Test
    void nonPositiveDuration_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> FrameSampler.sampleTargets(0));
        assertThrows(IllegalArgumentException.class, () -> FrameSampler.sampleTargets(-1.5));
        assertThrows(IllegalArgumentException.class, () -> FrameSampler.countFrames(0));
    }

    @Test
    void targets_coverEachSecondOnce() {
        // 5 seconds -> one target per second at half-second offsets.
        List<Double> targets = FrameSampler.sampleTargets(5.0);
        assertEquals(5, targets.size());
        for (int i = 0; i < targets.size(); i++) {
            assertEquals(i + 0.5, targets.get(i), 0.0001);
        }
    }

    @Test
    void targets_areEvenlySpaced() {
        for (double duration : new double[]{3.7, 10.0, 121.0, 240.0}) {
            List<Double> targets = FrameSampler.sampleTargets(duration);
            double spacing = duration / targets.size();
            for (int i = 0; i < targets.size(); i++) {
                assertEquals((i + 0.5) * spacing, targets.get(i), 0.000001,
                        "target " + i + " of duration " + duration);
            }
        }
    }

    @Test
    void targets_ascendingWithinDurationWithAtLeastOneSecondSpacing() {
        for (double duration : new double[]{0.99, 1.0, 3.7, 5.0, 59.7, 120.0, 121.0, 240.0}) {
            List<Double> targets = FrameSampler.sampleTargets(duration);
            assertFalse(targets.isEmpty(), "duration " + duration + "s must yield targets");
            assertTrue(targets.size() <= FrameSampler.MAX_FRAMES_PER_VIDEO,
                    "duration " + duration + "s must not exceed the frame cap");
            double previous = -1;
            for (double target : targets) {
                assertTrue(target >= 0 && target < duration,
                        "target " + target + " must be in [0, " + duration + ")");
                assertTrue(target > previous, "targets must be strictly ascending");
                if (previous >= 0) {
                    assertTrue(target - previous >= 1.0,
                            "consecutive targets must be at least one second apart");
                }
                previous = target;
            }
        }
    }
}