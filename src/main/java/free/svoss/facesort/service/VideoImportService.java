package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.TransactionRunner;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.util.HashUtils;
import free.svoss.facesort.util.ImageUtils;
import free.svoss.facesort.util.VideoFormats;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Orchestrates the import of video files into the Face Sort database.
 *
 * <p>For each video the service:
 * <ol>
 *   <li>Computes a SHA-256 content hash; a known video is skipped, or gets an
 *       additional stored path when it was found under a new location.</li>
 *   <li>Probes the duration, plans the {@link FrameSampler} targets, and reads
 *       the sampled frames from a {@link VideoFrameSource} (ffmpeg for real
 *       files, a fake in tests).</li>
 *   <li>Turns every sampled frame into an ordinary image row: a frame hash
 *       (SHA-256 of the full-frame JPEG), an {@code images} row, a thumbnail,
 *       a {@code video_frames} link, and the faces detected on it via the same
 *       shared pipeline that photos use. When the frame hash already exists
 *       (identical frame, or a photo with identical content) the existing
 *       image row is kept and only the {@code video_frames} link is added.</li>
 * </ol>
 *
 * <p>Videos are processed concurrently by an {@link ImportWorkerPool} of worker
 * threads, each with its own {@link FaceAiService}; all database access is
 * serialized through a single lock because the shared SQLite connection is not
 * thread-safe. Each video is processed independently; errors are counted but do
 * not abort the overall import.</p>
 */
public class VideoImportService implements AutoCloseable {

    /**
     * Supported video file extensions (case-insensitive, without the leading
     * dot). Package-private so tests can pin it to {@link VideoFormats}.
     */
    static final Set<String> SUPPORTED_EXTENSIONS = VideoFormats.supportedExtensions();

    private final ImageDao imageDao;
    private final FaceDao faceDao;
    private final VideoDao videoDao;
    private final List<FaceAiService> faceAiServices;
    private final ConfigModel config;
    private final VideoFrameSourceOpener sourceOpener;
    private final TransactionRunner transactionRunner;

    /**
     * Creates a video import service that processes videos sequentially through
     * a single {@link FaceAiService}.
     *
     * @param imageDao          DAO for the images and thumbnails tables
     * @param faceDao           DAO for the faces table
     * @param videoDao          DAO for the videos, video_paths and video_frames tables
     * @param faceAiService     face detection and embedding service
     * @param config            application configuration (detection thresholds, etc.)
     * @param transactionRunner runner for atomic multi-statement write units
     */
    public VideoImportService(ImageDao imageDao, FaceDao faceDao, VideoDao videoDao,
                              FaceAiService faceAiService, ConfigModel config,
                              TransactionRunner transactionRunner) {
        this(imageDao, faceDao, videoDao, List.of(faceAiService), config, transactionRunner);
    }

    /**
     * Creates a video import service that processes videos in parallel. One
     * worker thread is started per configured import thread, and each worker
     * uses its own {@link FaceAiService} from the given list, so at most
     * {@code faceAiServices.size()} workers can run concurrently.
     *
     * @param imageDao          DAO for the images and thumbnails tables
     * @param faceDao           DAO for the faces table
     * @param videoDao          DAO for the videos, video_paths and video_frames tables
     * @param faceAiServices    one face service per parallel worker (must not be empty)
     * @param config            application configuration (detection thresholds, etc.)
     * @param transactionRunner runner for atomic multi-statement write units
     */
    public VideoImportService(ImageDao imageDao, FaceDao faceDao, VideoDao videoDao,
                              List<FaceAiService> faceAiServices, ConfigModel config,
                              TransactionRunner transactionRunner) {
        this(imageDao, faceDao, videoDao, faceAiServices, config,
                FfmpegVideoFrameSource::new, transactionRunner);
    }

    /**
     * Creates a video import service with an explicit frame-source opener —
     * the test seam that keeps the service testable without the ffmpeg native
     * library.
     */
    VideoImportService(ImageDao imageDao, FaceDao faceDao, VideoDao videoDao,
                       List<FaceAiService> faceAiServices, ConfigModel config,
                       VideoFrameSourceOpener sourceOpener,
                       TransactionRunner transactionRunner) {
        this.imageDao = Objects.requireNonNull(imageDao, "imageDao");
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
        this.videoDao = Objects.requireNonNull(videoDao, "videoDao");
        if (faceAiServices == null || faceAiServices.isEmpty()) {
            throw new IllegalArgumentException("faceAiServices must not be empty");
        }
        this.faceAiServices = List.copyOf(faceAiServices);
        this.config = Objects.requireNonNull(config, "config");
        this.sourceOpener = Objects.requireNonNull(sourceOpener, "sourceOpener");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
    }

    /**
     * Recursively imports all supported video files from the given folder.
     *
     * @param folder   the root directory to scan
     * @param progress listener for progress messages (may be {@code null})
     * @return a summary of the import operation
     * @throws IOException if the folder cannot be traversed
     */
    public VideoImportResult importFolder(Path folder, ImportService.ProgressListener progress)
            throws IOException {
        return importFolder(folder, progress, () -> false);
    }

    /**
     * Recursively imports all supported video files from the given folder.
     *
     * <p>The import stops between videos as soon as {@code cancelled} reports
     * {@code true}; videos already processed remain in the database.</p>
     *
     * @param folder    the root directory to scan
     * @param progress  listener for progress messages (may be {@code null})
     * @param cancelled supplier consulted before each video; when it returns
     *                  {@code true} the import stops (may be {@code null})
     * @return a summary of the import operation
     * @throws IOException if the folder cannot be traversed
     */
    public VideoImportResult importFolder(Path folder, ImportService.ProgressListener progress,
                                          BooleanSupplier cancelled) throws IOException {
        List<Path> videoFiles = ImportFiles.collect(folder, SUPPORTED_EXTENSIONS, cancelled);
        if (videoFiles.isEmpty()) {
            return new VideoImportResult(0, 0, 0, 0, 0, 0, 0,
                    cancelled != null && cancelled.getAsBoolean());
        }
        int total = videoFiles.size();

        int minBbox = config.getMinBoundingBoxSize();
        double minConfidence = config.getMinConfidence();
        int maxFacesPerImage = config.getMaxFacesPerImage();
        int maxDetectionDimension = config.getMaxDetectionDimension();
        String criteriaJson = FaceDetectionUtils.buildCriteriaJson(minBbox, minConfidence,
                maxFacesPerImage);

        AtomicInteger newVideos = new AtomicInteger();
        AtomicInteger newFrames = new AtomicInteger();
        AtomicInteger newFaces = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();

        ImportWorkerPool.Result worker = ImportWorkerPool.run(
                config.getMaxImportThreads(), faceAiServices, "video-import-worker-",
                videoFiles, progress, cancelled,
                (service, file) -> {
                    VideoFileResult result = processVideo(file, criteriaJson, minBbox,
                            minConfidence, maxFacesPerImage, maxDetectionDimension, service);
                    if (result.newVideo) {
                        newVideos.incrementAndGet();
                    }
                    if (result.skipped) {
                        skipped.incrementAndGet();
                    }
                    newFrames.addAndGet(result.framesAdded);
                    newFaces.addAndGet(result.facesAdded);
                    return result.droppedFaces;
                });

        boolean wasCancelled = worker.stopped()
                || (cancelled != null && cancelled.getAsBoolean());
        return new VideoImportResult(total, newVideos.get(), newFrames.get(), newFaces.get(),
                skipped.get(), worker.errors(), worker.processed(), wasCancelled);
    }

    /**
     * Processes a single video file: register the video, extract every sampled
     * frame, store the frame + thumbnail + faces, and link the frames.
     */
    private VideoFileResult processVideo(Path file, String criteriaJson,
                                         int minBbox, double minConfidence,
                                         int maxFacesPerImage, int maxDetectionDimension,
                                         FaceAiService service) throws Exception {

        String hash = HashUtils.hashFile(file);
        String absolutePath = file.toAbsolutePath().toString();

        // ---- Known-video branch: one serialized unit ----
        if (videoDao.exists(hash)) {
            if (videoDao.hasPath(hash, absolutePath)) {
                return VideoFileResult.skipped();
            }
            videoDao.addPath(hash, absolutePath);
            return VideoFileResult.newPath();
        }

        // ---- New-video branch: heavy work happens outside the DB ----
        int framesAdded = 0;
        int facesAdded = 0;
        int droppedFaces = 0;
        try (VideoFrameSource source = sourceOpener.open(file)) {
            double duration = source.getDuration();
            List<Double> targets = FrameSampler.sampleTargets(duration);
            long detectionTs = System.currentTimeMillis();

            // Register the video row before extracting any frames so the frame
            // links can resolve their foreign key.
            videoDao.insert(hash, detectionTs, criteriaJson, duration);
            videoDao.addPath(hash, absolutePath);

            for (double target : targets) {
                VideoFrameSource.SampledFrame sampled = source.seekTo(target);
                if (sampled == null) {
                    break; // stream ended before the target
                }
                BufferedImage frame = sampled.image();
                long timestampMs = Math.round(sampled.positionSeconds() * 1000);
                byte[] frameJpg = ImageUtils.toJpegBytes(frame, Thumbnailer.JPEG_QUALITY);
                String frameHash = HashUtils.hashBytes(frameJpg);

                // Frame content already known (identical frame, or a photo with
                // identical content): keep the existing image row and only link
                // it, so costly detection runs only for genuinely new frames.
                boolean knownFrame = imageDao.exists(frameHash);
                FaceDetectionUtils.DetectionResult detection = knownFrame
                        ? new FaceDetectionUtils.DetectionResult(List.of(), 0)
                        : FaceDetectionUtils.detectFaces(frameHash, frame,
                                maxDetectionDimension, minBbox, minConfidence,
                                maxFacesPerImage, service,
                                file + " @ " + timestampMs + " ms");
                List<FaceRecord> faceRecords = detection.faces();

                // The thumbnail is encoded before the transaction starts: JPEG
                // encoding must never run while holding the connection monitor.
                byte[] thumbJpg = knownFrame ? null
                    : Thumbnailer.encode(frame, config.getThumbnailSize());

                // ---- One atomic unit per frame (insert + thumbnail + faces
                //      + link) ----
                FrameOutcome outcome = transactionRunner.inTransaction(() -> {
                    boolean stored = false;
                    if (!imageDao.exists(frameHash)) {
                        // New frame: image row + thumbnail + faces.
                        imageDao.insert(frameHash, detectionTs, criteriaJson,
                                faceRecords.size());
                        imageDao.saveThumbnail(frameHash, thumbJpg);
                        for (FaceRecord faceRecord : faceRecords) {
                            faceDao.insert(faceRecord);
                        }
                        stored = true;
                    }
                    // A link is dropped when another video already imported
                    // the same frame (a frame belongs to at most one video).
                    videoDao.linkFrame(frameHash, hash, timestampMs);
                    return new FrameOutcome(stored, faceRecords.size());
                });

                droppedFaces += detection.droppedFaces();
                if (outcome.storedNewFrame) {
                    framesAdded++;
                    facesAdded += outcome.facesAdded;
                }
            }

            return VideoFileResult.newVideo(framesAdded, facesAdded, droppedFaces);
        }
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        for (FaceAiService service : faceAiServices) {
            try {
                service.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    // ---- Public inner types ----

    /**
     * Immutable summary of a video import operation.
     *
     * @param totalVideos total video files encountered
     * @param newVideos   videos newly added to the database
     * @param newFrames   frame images newly added to the database
     * @param newFaces    face records created
     * @param skipped     files already fully known (video + path)
     * @param errors      files that failed processing or lost faces during detection
     * @param processed   files actually processed before the import stopped
     * @param wasCancelled true if the import was stopped via the cancellation supplier
     */
    public record VideoImportResult(
            int totalVideos,
            int newVideos,
            int newFrames,
            int newFaces,
            int skipped,
            int errors,
            int processed,
            boolean wasCancelled
    ) {
    }

    // ---- Private inner type ----

    /**
     * Outcome of storing one sampled frame.
     *
     * @param storedNewFrame whether a new image row (thumbnail + faces) was written
     * @param facesAdded     faces detected on the frame when it was new
     */
    private record FrameOutcome(boolean storedNewFrame, int facesAdded) {
    }

    /**
     * Internal result of processing a single video file.
     */
    private static final class VideoFileResult {
        final boolean newVideo;
        final boolean skipped;
        final int framesAdded;
        final int facesAdded;
        final int droppedFaces;

        private VideoFileResult(boolean newVideo, boolean skipped,
                                int framesAdded, int facesAdded, int droppedFaces) {
            this.newVideo = newVideo;
            this.skipped = skipped;
            this.framesAdded = framesAdded;
            this.facesAdded = facesAdded;
            this.droppedFaces = droppedFaces;
        }

        static VideoFileResult newVideo(int frames, int faces, int droppedFaces) {
            return new VideoFileResult(true, false, frames, faces, droppedFaces);
        }

        static VideoFileResult newPath() {
            return new VideoFileResult(false, false, 0, 0, 0);
        }

        static VideoFileResult skipped() {
            return new VideoFileResult(false, true, 0, 0, 0);
        }
    }
}