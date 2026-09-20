package free.svoss.facesort.service;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the {@link VideoFrameSource} contract — the seam that the real
 * {@code FfmpegVideoFrameSource} must honor — driven by the in-memory
 * {@link FakeVideoFrameSource}.
 */
class VideoFrameSourceTest {

    private static int argb(Color color) {
        return (0xFF << 24) | (color.getRed() << 16)
                | (color.getGreen() << 8) | color.getBlue();
    }

    @Test
    void durationProbe_returnsConfiguredDuration() {
        try (FakeVideoFrameSource source = new FakeVideoFrameSource(28.7, 0.5, 1.5, 2.5)) {
            assertEquals(28.7, source.getDuration());
        }
    }

    @Test
    void samplerTargets_extractOneFramePerTargetAscending() {
        double[] positions = {0.5, 1.5, 2.5, 3.5, 4.5};
        try (FakeVideoFrameSource source = new FakeVideoFrameSource(5.0, positions)) {
            List<Double> targets = FrameSampler.sampleTargets(source.getDuration());
            assertEquals(5, targets.size());

            List<Double> delivered = new ArrayList<>();
            for (double target : targets) {
                VideoFrameSource.SampledFrame frame = source.seekTo(target);
                assertNotNull(frame, "expected a frame for every sampler target");
                delivered.add(frame.positionSeconds());
            }
            assertEquals(targets, delivered,
                    "the sampled frames land exactly on the sampling targets, in order");
        }
    }

    @Test
    void seekTo_deliversFrameAndItsRealPosition() {
        try (FakeVideoFrameSource source = new FakeVideoFrameSource(5.0, 0.5, 1.5, 2.5)) {
            VideoFrameSource.SampledFrame frame = source.seekTo(0.5);
            assertNotNull(frame);
            assertEquals(0.5, frame.positionSeconds());
            Color expected = FakeVideoFrameSource.frameColor(0.5);
            assertEquals(argb(expected), frame.image().getRGB(0, 0),
                    "frame image matches the expected content for that position");
            assertEquals(argb(expected), frame.image().getRGB(3, 3),
                    "frame image is solid (same color across pixels)");
        }
    }

    @Test
    void seekToLaterTarget_landsOnFirstFrameAtOrAfterTarget() {
        try (FakeVideoFrameSource source = new FakeVideoFrameSource(5.0,
                0.5, 1.5, 2.5, 3.5, 4.5)) {
            VideoFrameSource.SampledFrame frame = source.seekTo(2.0);
            assertNotNull(frame);
            assertEquals(2.5, frame.positionSeconds(),
                    "seeking to 2.0 s must serve the first frame at or after the target");
        }
    }

    @Test
    void rewinding_throwsIllegalStateException() {
        try (FakeVideoFrameSource source = new FakeVideoFrameSource(5.0,
                0.5, 1.5, 2.5, 3.5, 4.5)) {
            source.seekTo(2.5);
            assertThrows(IllegalStateException.class, () -> source.seekTo(1.5),
                    "the source is forward-only and must reject a rewind");
        }
    }

    @Test
    void seekingPastLastFrame_returnsNull_gracefulEof() {
        try (FakeVideoFrameSource source = new FakeVideoFrameSource(5.0,
                0.5, 1.5, 2.5)) {
            assertNotNull(source.seekTo(0.5));
            assertNotNull(source.seekTo(1.5));
            assertNotNull(source.seekTo(2.5));
            assertNull(source.seekTo(3.5),
                    "once the stream is exhausted, seeking returns null so extraction stops gracefully");
        }
    }

    @Test
    void seekingPastEnd_skipsAllFramesAndReturnsNull() {
        try (FakeVideoFrameSource source = new FakeVideoFrameSource(5.0,
                0.5, 1.5, 2.5, 3.5, 4.5)) {
            assertNull(source.seekTo(10.0),
                    "a target beyond every frame position must signal end of stream");
        }
    }

    @Test
    void emptySource_returnsNullOnFirstSeek() {
        try (FakeVideoFrameSource source = new FakeVideoFrameSource(5.0)) {
            assertNull(source.seekTo(0.5));
        }
    }
}