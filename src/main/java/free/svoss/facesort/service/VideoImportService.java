package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.VideoFrameLinkRecord;
import free.svoss.facesort.util.HashUtils;
import free.svoss.facesort.util.ImageUtils;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 * <p>Videos are processed concurrently by a pool of worker threads, each with
 * its own {@link FaceAiService}; all database access is serialized through a
 * single lock because the shared SQLite connection is not thread-safe. Each
 * video is processed independently; errors are counted but do not abort the
 * overall import.</p>
 */
public class VideoImportService implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(VideoImportService.class.getName());

    /** JPEG quality for frame thumbnails and frame hashing. */
    private static final float JPEG_QUALITY = 0.85f;

    /** Supported video file extensions (case-insensitive, without the leading dot). */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "mp4", "mkv", "mov", "avi", "webm", "m4v", "flv", "mpg", "mpeg",
            "3gp", "ts", "wmv"
    );

    private final ImageDao imageDao;
    private final FaceDao faceDao;
    private final VideoDao videoDao;
    private final List<FaceAiService> faceAiServices;
    private final ConfigModel config;
    private final VideoFrameSourceOpener sourceOpener;

    /**
     * Creates a video import service that processes videos sequentially through
     * a single {@link FaceAiService}.
     *
     * @param imageDao      DAO for the images and thumbnails tables
     * @param faceDao       DAO for the faces table
     * @param videoDao      DAO for the videos, video_paths and video_frames tables
     * @param faceAiService face detection and embedding service
     * @param config        application configuration (detection thresholds, etc.)
     */
    public VideoImportService(ImageDao imageDao, FaceDao faceDao, VideoDao videoDao,
                              FaceAiService faceAiService, ConfigModel config) {
        this(imageDao, faceDao, videoDao, List.of(faceAiService), config);
    }

    /**
     * Creates a video import service that processes videos in parallel. One
     * worker thread is started per configured import thread, and each worker
     * uses its own {@link FaceAiService} from the given list, so at most
     * {@code faceAiServices.size()} workers can run concurrently.
     *
     * @param imageDao       DAO for the images and thumbnails tables
     * @param faceDao        DAO for the faces table
     * @param videoDao       DAO for the videos, video_paths and video_frames tables
     * @param faceAiServices one face service per parallel worker (must not be empty)
     * @param config         application configuration (detection thresholds, etc.)
     */
    public VideoImportService(ImageDao imageDao, FaceDao faceDao, VideoDao videoDao,
                              List<FaceAiService> faceAiServices, ConfigModel config) {
        this(imageDao, faceDao, videoDao, faceAiServices, config,
                FfmpegVideoFrameSource::new);
    }

    /**
     * Creates a video import service with an explicit frame-source opener —
     * the test seam that keeps the service testable without the ffmpeg native
     * library.
     */
    VideoImportService(ImageDao imageDao, FaceDao faceDao, VideoDao videoDao,
                       List<FaceAiService> faceAiServices, ConfigModel config,
                       VideoFrameSourceOpener sourceOpener) {
        this.imageDao = Objects.requireNonNull(imageDao, "imageDao");
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
        this.videoDao = Objects.requireNonNull(videoDao, "videoDao");
        if (faceAiServices == null || faceAiServices.isEmpty()) {
            throw new IllegalArgumentException("faceAiServices must not be empty");
        }
        this.faceAiServices = List.copyOf(faceAiServices);
        this.config = Objects.requireNonNull(config, "config");
        this.sourceOpener = Objects.requireNonNull(sourceOpener, "sourceOpener");
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
        List<Path> videoFiles = collectVideoFiles(folder, cancelled);
        if (videoFiles.isEmpty()) {
            return new VideoImportResult(0, 0, 0, 0, 0, 0, 0,
                    cancelled != null && cancelled.getAsBoolean());
        }
        int total = videoFiles.size();

        int threads = Math.min(Math.max(1, config.getMaxImportThreads()), faceAiServices.size());

        int minBbox = config.getMinBoundingBoxSize();
        double minConfidence = config.getMinConfidence();
        int maxFacesPerImage = config.getMaxFacesPerImage();
        int maxDetectionDimension = config.getMaxDetectionDimension();
        String criteriaJson = FaceDetectionUtils.buildCriteriaJson(minBbox, minConfidence,
                maxFacesPerImage);

        AtomicInteger nextFile = new AtomicInteger();
        AtomicInteger newVideos = new AtomicInteger();
        AtomicInteger newFrames = new AtomicInteger();
        AtomicInteger newFaces = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger processed = new AtomicInteger();
        AtomicBoolean stopped = new AtomicBoolean();

        AtomicInteger workerIds = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable,
                    "video-import-worker-" + workerIds.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int w = 0; w < threads; w++) {
                final FaceAiService service = faceAiServices.get(w % faceAiServices.size());
                futures.add(pool.submit(() -> runWorker(service, videoFiles, total, criteriaJson,
                        minBbox, minConfidence, maxFacesPerImage, maxDetectionDimension,
                        progress, cancelled, nextFile, newVideos, newFrames, newFaces,
                        skipped, errors, processed, stopped)));
            }
        } finally {
            pool.shutdown();
            awaitWorkerCompletion(futures);
        }

        boolean wasCancelled = stopped.get()
                || (cancelled != null && cancelled.getAsBoolean());
        return new VideoImportResult(total, newVideos.get(), newFrames.get(), newFaces.get(),
                skipped.get(), errors.get(), processed.get(), wasCancelled);
    }

    /**
     * Single worker loop: pulls video indices from the shared counter until the
     * import is cancelled or all videos are processed.
     */
    private void runWorker(FaceAiService service, List<Path> videoFiles, int total,
                           String criteriaJson, int minBbox, double minConfidence,
                           int maxFacesPerImage, int maxDetectionDimension,
                           ImportService.ProgressListener progress, BooleanSupplier cancelled,
                           AtomicInteger nextFile, AtomicInteger newVideos,
                           AtomicInteger newFrames, AtomicInteger newFaces,
                           AtomicInteger skipped, AtomicInteger errors,
                           AtomicInteger processed, AtomicBoolean stopped) {
        for (int i = nextFile.getAndIncrement(); i < total; i = nextFile.getAndIncrement()) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                stopped.set(true);
                return;
            }
            Path file = videoFiles.get(i);
            int index = i + 1;
            reportProgress(progress, String.format(Locale.ROOT,
                    "Started %d/%d: %s", index, total, file.getFileName()));
            try {
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
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error processing video: " + file, e);
                errors.incrementAndGet();
            }
            processed.incrementAndGet();
            reportProgress(progress, String.format(Locale.ROOT,
                    "Completed %d/%d: %s", index, total, file.getFileName()));
        }
    }

    private static void reportProgress(ImportService.ProgressListener progress, String message) {
        if (progress != null) {
            synchronized (progress) {
                progress.onProgress(message);
            }
        }
    }

    /**
     * Blocks until every worker future completes. An interruption stops the
     * join and returns the current aggregate; a worker that dies unexpectedly
     * is logged and the remaining workers are still joined.
     */
    private static void awaitWorkerCompletion(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                LOG.log(Level.SEVERE, "Video import worker failed unexpectedly", e.getCause());
            }
        }
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
        int linkedFrames = 0;
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
                byte[] frameJpg = ImageUtils.toJpegBytes(frame, JPEG_QUALITY);
                String frameHash = HashUtils.hashBytes(frameJpg);

                // Frame content already known (identical frame, or a photo with
                // identical content): keep the existing image row and only link
                // it, so costly detection runs only for genuinely new frames.
                boolean knownFrame = imageDao.exists(frameHash);
                List<FaceRecord> faceRecords = knownFrame ? List.of()
                        : FaceDetectionUtils.detectFaces(frameHash, frame,
                                maxDetectionDimension, minBbox, minConfidence,
                                maxFacesPerImage, service,
                                file + " @ " + timestampMs + " ms");

                boolean newFrame = false;
                try {
                    if (!imageDao.exists(frameHash)) {
                        // New frame: image row + thumbnail + faces.
                        imageDao.insert(frameHash, detectionTs, criteriaJson,
                                faceRecords.size());
                        imageDao.saveThumbnail(frameHash, encodeThumbnail(frame));
                        for (FaceRecord faceRecord : faceRecords) {
                            faceDao.insert(faceRecord);
                        }
                        newFrame = true;
                    }
                } catch (SQLException e) {
                    // Another worker (video or photo import) stored the same
                    // frame first while this worker was detecting: keep the
                    // existing image row and only link it below. A genuine
                    // failure still propagates.
                    if (!imageDao.exists(frameHash)) {
                        throw e;
                    }
                }
                // The link is dropped when another video already imported
                // the same frame (a frame belongs to at most one video), so
                // linkedFrames only counts links that actually exist.
                linkedFrames += videoDao.linkFrame(frameHash, hash, timestampMs);
                if (newFrame) {
                    framesAdded++;
                    facesAdded += faceRecords.size();
                }
            }

            // Persist the final counts on the video row, derived from the links
            // and their stored faces so the videos row stays consistent with
            // the video_frames and images tables.
            int linkedFaces = 0;
            for (VideoFrameLinkRecord link : videoDao.findFramesForVideo(hash)) {
                linkedFaces += faceDao.findByImageHash(link.frameHash()).size();
            }
            videoDao.updateVideoCounts(hash, linkedFrames, linkedFaces);
            return VideoFileResult.newVideo(framesAdded, facesAdded);
        }
    }

    private byte[] encodeThumbnail(BufferedImage image) throws IOException {
        int thumbSize = config.getThumbnailSize();
        BufferedImage thumbnail = ImageUtils.downsize(image, thumbSize);
        return ImageUtils.toJpegBytes(thumbnail, JPEG_QUALITY);
    }

    // ---- File collection ----

    /**
     * Recursively collects all supported video files under {@code root}.
     *
     * <p>The walk terminates as soon as {@code cancelled} reports {@code true},
     * potentially returning a partial list.</p>
     *
     * @param root      the root directory to scan
     * @param cancelled supplier consulted before each file and directory; when
     *                  it returns {@code true} the scan stops (may be {@code null})
     */
    private List<Path> collectVideoFiles(Path root, BooleanSupplier cancelled) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }
                if (attrs.isRegularFile() && isSupportedVideo(file)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return (cancelled != null && cancelled.getAsBoolean())
                        ? FileVisitResult.TERMINATE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                LOG.log(Level.WARNING, "Cannot access file: " + file, exc);
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /**
     * Returns {@code true} if the file has a supported video extension.
     */
    private static boolean isSupportedVideo(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return SUPPORTED_EXTENSIONS.contains(name.substring(dot + 1));
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
     * @param errors      files that failed processing
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
     * Internal result of processing a single video file.
     */
    private static final class VideoFileResult {
        final boolean newVideo;
        final boolean skipped;
        final int framesAdded;
        final int facesAdded;

        private VideoFileResult(boolean newVideo, boolean skipped,
                                int framesAdded, int facesAdded) {
            this.newVideo = newVideo;
            this.skipped = skipped;
            this.framesAdded = framesAdded;
            this.facesAdded = facesAdded;
        }

        static VideoFileResult newVideo(int frames, int faces) {
            return new VideoFileResult(true, false, frames, faces);
        }

        static VideoFileResult newPath() {
            return new VideoFileResult(false, false, 0, 0);
        }

        static VideoFileResult skipped() {
            return new VideoFileResult(false, true, 0, 0);
        }
    }
}