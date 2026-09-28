package free.svoss.facesort.service;

import com.github.manevolent.ffmpeg4j.FFmpegIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Native resource contract of the real {@link FfmpegVideoFrameSource}, driven
 * against the ffmpeg natives with the pure-Java videos from {@link TinyAviVideo}.
 *
 * <p>Opening a video allocates native handles — an AVIO context, an
 * AVFormatContext and a source stream — that the garbage collector never
 * reclaims, so every handle a completed <em>or</em> failed open created has to
 * be closed by hand. {@link FFmpegIO#getStatesInUse()} counts the live native
 * I/O states, which makes a leak observable as a counter that grows once per
 * attempt rather than as slowly rising process memory.</p>
 */
class FfmpegVideoFrameSourceTest {

    /** How often a file is opened, so a per-attempt leak cannot hide in the noise. */
    private static final int ATTEMPTS = 3;

    @TempDir
    Path tempDir;

    @Test
    void undecodableStream_isRejectedWithoutLeakingNativeResources() throws Exception {
        Path video = TinyAviVideo.writeUndecodable(tempDir.resolve("undecodable.avi"), 64, 48, 5);

        int statesInUseBefore = FFmpegIO.getStatesInUse();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            assertThrows(IOException.class, () -> new FfmpegVideoFrameSource(video),
                    "attempt " + attempt + ": a video stream with no decodable pixel format"
                            + " must be rejected with a clean IOException");
        }
        assertEquals(statesInUseBefore, FFmpegIO.getStatesInUse(),
                "a failed open must release the native I/O state it already allocated;"
                        + " a video rejected after its handles are open used to leak them");
    }

    @Test
    void unreadableContainer_isRejectedWithoutLeakingNativeResources() throws Exception {
        Path broken = tempDir.resolve("broken.mp4");
        Files.writeString(broken, "definitely not a video file");

        int statesInUseBefore = FFmpegIO.getStatesInUse();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            assertThrows(IOException.class, () -> new FfmpegVideoFrameSource(broken),
                    "attempt " + attempt + ": a file no demuxer recognises must be rejected");
        }
        assertEquals(statesInUseBefore, FFmpegIO.getStatesInUse(),
                "a failed open must release the native I/O state it already allocated");
    }

    @Test
    void completedOpens_doNotRetainNativeResources() throws Exception {
        Path video = TinyAviVideo.write(tempDir.resolve("sample.avi"), 64, 48, 5);

        int statesInUseBefore = FFmpegIO.getStatesInUse();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try (FfmpegVideoFrameSource source = new FfmpegVideoFrameSource(video)) {
                assertNotNull(source.seekTo(0.5),
                        "attempt " + attempt + ": a decodable video must yield a frame");
            }
        }
        assertEquals(statesInUseBefore, FFmpegIO.getStatesInUse(),
                "an open/close cycle must release every native handle it created");
    }
}
