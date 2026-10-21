package free.svoss.facesort.service;

import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.util.ImageUtils;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link FaceImageSource}: resolves a stored hash back to the photo
 * file or video frame it was imported from.
 *
 * <p>A hash first has to resolve to a photo. When none of its stored photo
 * paths still yields an image, it is looked up as a video frame: the frame's
 * video file is opened and the frame is decoded at the stored timestamp.</p>
 *
 * <p>Video frame sources seek forward only, so the open source is reused while
 * the same video stays in play and the requested position keeps moving forward;
 * otherwise it is closed and reopened. Callers that walk a library in stored
 * order therefore decode each video in a single pass.</p>
 */
final class StoredFaceImageSource implements FaceImageSource {

    private static final Logger LOG = Logger.getLogger(StoredFaceImageSource.class.getName());

    private final ImageDao imageDao;
    private final VideoDao videoDao;
    private final VideoFrameSourceOpener opener;
    private final MediaFileResolver resolver;

    private String openVideoHash;
    private Path openVideoFile;
    private VideoFrameSource openSource;
    private double lastPositionSeconds = -1.0;

    /**
     * @param imageDao photo path lookup; must not be null
     * @param videoDao video and video frame lookup; must not be null
     * @param opener   opens a frame source for a video file; must not be null
     */
    StoredFaceImageSource(ImageDao imageDao, VideoDao videoDao, VideoFrameSourceOpener opener) {
        this.imageDao = Objects.requireNonNull(imageDao, "imageDao must not be null");
        this.videoDao = Objects.requireNonNull(videoDao, "videoDao must not be null");
        this.opener = Objects.requireNonNull(opener, "opener must not be null");
        this.resolver = new MediaFileResolver(imageDao, videoDao);
    }

    @Override
    public BufferedImage load(String imageHash) throws IOException, SQLException {
        Objects.requireNonNull(imageHash, "imageHash must not be null");
        BufferedImage photo = loadPhoto(imageHash);
        return photo != null ? photo : loadVideoFrame(imageHash);
    }

    /**
     * Tries every stored photo path in turn, skipping the ones that no longer
     * exist or cannot be decoded. Returns {@code null} when none of them yields
     * an image, so the caller can fall back to the video lookup.
     */
    private BufferedImage loadPhoto(String imageHash) throws SQLException {
        for (String storedPath : imageDao.getPaths(imageHash)) {
            Path file = toPath(storedPath);
            if (file == null || !Files.isReadable(file)) {
                continue;
            }
            try {
                return ImageUtils.readImage(file);
            } catch (IOException e) {
                LOG.log(Level.FINE, "Skipping unreadable photo " + file, e);
            }
        }
        return null;
    }

    private BufferedImage loadVideoFrame(String imageHash) throws IOException, SQLException {
        Optional<String> videoHash = videoDao.findVideoHash(imageHash);
        if (videoHash.isEmpty()) {
            return null;
        }
        Long timestampMs = videoDao.findTimestamp(imageHash);
        if (timestampMs == null) {
            return null;
        }
        Path file = resolver.firstReadablePath(videoDao.getPaths(videoHash.get())).orElse(null);
        if (file == null) {
            return null;
        }
        double targetSeconds = timestampMs / 1000.0;
        VideoFrameSource source = sourceFor(videoHash.get(), file, targetSeconds);
        VideoFrameSource.SampledFrame frame = source.seekTo(targetSeconds);
        if (frame == null) {
            return null;
        }
        // seekTo only moves forward, so the source may be reused up to (and
        // including) the position just requested.
        lastPositionSeconds = targetSeconds;
        return frame.image();
    }

    /**
     * Returns the open source when it is already positioned on the same video
     * at or before the target, and reopens it otherwise.
     */
    private VideoFrameSource sourceFor(String videoHash, Path file, double targetSeconds)
            throws IOException {
        if (openSource != null
                && videoHash.equals(openVideoHash)
                && file.equals(openVideoFile)
                && targetSeconds >= lastPositionSeconds) {
            return openSource;
        }
        closeSource();
        VideoFrameSource source = opener.open(file);
        openVideoHash = videoHash;
        openVideoFile = file;
        openSource = source;
        lastPositionSeconds = -1.0;
        return source;
    }

    @Override
    public void close() {
        closeSource();
    }

    private void closeSource() {
        if (openSource != null) {
            openSource.close();
            openSource = null;
            openVideoHash = null;
            openVideoFile = null;
            lastPositionSeconds = -1.0;
        }
    }

    private static Path toPath(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return null;
        }
        try {
            return Path.of(storedPath);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
