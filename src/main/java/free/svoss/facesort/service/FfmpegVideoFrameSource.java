package free.svoss.facesort.service;

import com.github.manevolent.ffmpeg4j.FFmpegIO;
import com.github.manevolent.ffmpeg4j.FFmpegInput;
import com.github.manevolent.ffmpeg4j.FFmpegException;
import com.github.manevolent.ffmpeg4j.VideoFrame;
import com.github.manevolent.ffmpeg4j.source.VideoSourceSubstream;
import com.github.manevolent.ffmpeg4j.stream.source.FFmpegSourceStream;
import free.svoss.facesort.util.VideoFormats;
import free.svoss.facesort.util.VideoFrameUtils;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.global.avutil;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Real {@link VideoFrameSource} implementation backed by ffmpeg4j (bytedeco
 * FFmpeg via JNI — no external {@code ffmpeg.exe} needed).
 *
 * <p>Opening a video registers all substreams, picks the video substream and
 * probes the duration once (container duration, or a cheap packet scan when
 * the container does not report one). Extraction is a single monotonic pass:
 * for each ascending target the source decodes forward until the first frame
 * at or after the target and reports that frame's real position, which can
 * differ slightly from the requested target (e.g. long GOPs only materialize
 * decodable frames at keyframe boundaries).</p>
 */
public final class FfmpegVideoFrameSource implements VideoFrameSource {

    private static final Logger LOG = Logger.getLogger(FfmpegVideoFrameSource.class.getName());

    private final Path file;
    private final Session session;
    private final double duration;

    private double lastTarget = -1.0;
    private boolean closed;

    /**
     * Opens the given video file and probes its duration.
     *
     * @param file the video file to read; must not be null
     * @throws IOException if the file cannot be opened, contains no decodable
     *                     video stream, or its duration cannot be determined
     */
    public FfmpegVideoFrameSource(Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        Session probed = Session.open(file);
        try {
            double containerDuration = containerDuration(probed);
            if (containerDuration > 0) {
                this.duration = containerDuration;
                this.session = probed;
            } else {
                // The container does not report a usable duration: fall back to
                // a cheap packet scan (decoding switched off), then open the
                // file again because the scan consumed the stream to EOF.
                this.duration = probeByPacketScan(probed);
                probed.close();
                this.session = Session.open(file);
            }
        } catch (IOException | RuntimeException e) {
            probed.close();
            throw e;
        }
    }

    @Override
    public double getDuration() {
        return duration;
    }

    @Override
    public SampledFrame seekTo(double targetSeconds) throws IOException {
        if (closed) {
            throw new IllegalStateException("source is already closed");
        }
        if (targetSeconds < lastTarget) {
            throw new IllegalStateException("seek must be forward-only: target "
                    + targetSeconds + " s is behind " + lastTarget + " s");
        }
        lastTarget = targetSeconds;

        // The underlying stream can only be consumed in one forward direction
        // (it is built on a plain InputStream), so extraction decodes every
        // frame exactly once and captures the first frame at or after each
        // requested target as it passes.
        while (true) {
            VideoFrame frame;
            try {
                frame = session.videoStream.next();
            } catch (EOFException e) {
                return null; // no more frames -> graceful end of extraction
            }
            if (frame == null) {
                return null;
            }
            if (frame.getPosition() < targetSeconds) {
                continue; // still before the requested target
            }
            return new SampledFrame(
                    VideoFrameUtils.rgb24ToImage(frame.getData(), frame.getWidth(), frame.getHeight()),
                    frame.getPosition());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        session.close();
    }

    @Override
    public String toString() {
        return "FfmpegVideoFrameSource{" + file + ", " + duration + " s}";
    }

    /**
     * Returns the duration reported by the format context, or {@code -1} when
     * the container does not report a usable one.
     */
    private static double containerDuration(Session session) {
        long micros = session.input.getFormatContext().duration();
        if (micros == avutil.AV_NOPTS_VALUE || micros <= 0) {
            return -1.0;
        }
        return micros / 1_000_000d;
    }

    /**
     * Probes the duration cheaply by scanning packets with decoding switched
     * off. Consumes the stream to EOF; the caller must reopen afterwards.
     */
    private static double probeByPacketScan(Session session) throws IOException {
        session.videoStream.setDecoding(false);
        try {
            while (true) {
                session.sourceStream.readPacket();
            }
        } catch (EOFException expected) {
            // stream fully consumed; the source keeps the last packet's position
        }
        double position = session.sourceStream.getPosition();
        if (position <= 0) {
            throw new IOException("cannot determine duration of video (no readable packets): "
                    + session.file);
        }
        return position;
    }

    /**
     * Extracts the file extension and resolves the ffmpeg demuxer format name
     * via {@link VideoFormats}.
     */
    private static String demuxerName(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return VideoFormats.demuxerName(ext);
    }

    /**
     * Fails cleanly when the video stream has no decodable pixel format.
     * ffmpeg4j builds its swscale conversion context from the codec's pixel
     * format; passing {@code AV_PIX_FMT_NONE} (as happens with files whose
     * header is unreadable) aborts the whole JVM natively, so it must be
     * detected before any streams are registered.
     */
    private static void checkVideoDecodable(FFmpegInput input) throws IOException {
        AVFormatContext context = input.getFormatContext();
        for (int i = 0; i < context.nb_streams(); i++) {
            AVStream stream = context.streams(i);
            if (stream.codecpar().codec_type() == avutil.AVMEDIA_TYPE_VIDEO
                    && stream.codecpar().format() == avutil.AV_PIX_FMT_NONE) {
                throw new IOException("video stream has no decodable pixel format");
            }
        }
    }

    /**
     * Holds the open ffmpeg chain (I/O context, input, source stream and the
     * video substream) for one video file.
     */
    private static final class Session implements AutoCloseable {

        final Path file;
        final FFmpegIO io;
        final FFmpegInput input;
        final FFmpegSourceStream sourceStream;
        final VideoSourceSubstream videoStream;

        private boolean closed;

        private Session(Path file, FFmpegIO io, FFmpegInput input,
                        FFmpegSourceStream sourceStream, VideoSourceSubstream videoStream) {
            this.file = file;
            this.io = io;
            this.input = input;
            this.sourceStream = sourceStream;
            this.videoStream = videoStream;
        }

        /**
         * Opens a video file and prepares its video substream for decoding.
         *
         * @throws IOException if the file cannot be opened or contains no
         *                     decodable video stream
         */
        static Session open(Path file) throws IOException {
            String demuxer = demuxerName(file);
            FFmpegIO io = null;
            FFmpegInput input = null;
            FFmpegSourceStream sourceStream = null;
            try {
                io = FFmpegIO.openInput(file.toFile(), FFmpegIO.DEFAULT_BUFFER_SIZE);
                input = new FFmpegInput(io);
                sourceStream = input.open(demuxer);
                checkVideoDecodable(input);
                sourceStream.registerStreams();
                VideoSourceSubstream videoStream = (VideoSourceSubstream) sourceStream
                        .getSubstreams(VideoSourceSubstream.class).stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "no video stream in " + file));
                return new Session(file, io, input, sourceStream, videoStream);
            } catch (FFmpegException e) {
                closeQuietly(sourceStream);
                closeQuietly(input);
                closeQuietly(io);
                throw new IOException("Failed to open video: " + file, e);
            } catch (RuntimeException e) {
                closeQuietly(sourceStream);
                closeQuietly(input);
                closeQuietly(io);
                throw e;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                sourceStream.close();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error closing video frame source for " + file, e);
            }
        }

        private static void closeQuietly(AutoCloseable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error closing during failed open of " + closeable, e);
            }
        }
    }
}