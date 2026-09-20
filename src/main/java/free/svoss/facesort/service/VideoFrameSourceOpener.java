package free.svoss.facesort.service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Testable seam for opening a {@link VideoFrameSource} for a video file. The
 * production implementation is {@link FfmpegVideoFrameSource#FfmpegVideoFrameSource(Path)};
 * tests supply an in-memory source instead so the service runs without the
 * ffmpeg native library.
 */
@FunctionalInterface
interface VideoFrameSourceOpener {

    /**
     * Opens a frame source for the given video file.
     *
     * @param file path to the video file
     * @return an open, ready-to-read frame source
     * @throws IOException if the video cannot be opened
     */
    VideoFrameSource open(Path file) throws IOException;
}