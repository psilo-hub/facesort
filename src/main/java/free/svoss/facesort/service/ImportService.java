package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.TransactionRunner;
import free.svoss.facesort.model.FaceRecord;
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
 * Orchestrates the import of image files into the Face Sort database.
 *
 * <p>For each image file the service:
 * <ol>
 *   <li>Computes a SHA-256 content hash.</li>
 *   <li>If the image is new, runs face detection and stores qualifying faces.
 *       Images larger than the configured detection dimension are scaled down
 *       first so detection stays fast and cheap on high-resolution photos; the
 *       resulting bounding boxes are mapped back to the original coordinates.</li>
 *   <li>If the image exists but from a new path, records the additional path.</li>
 *   <li>Generates a thumbnail if one is not already stored.</li>
 * </ol>
 *
 * <p>Files are processed concurrently by a pool of worker threads, each with
 * its own {@link FaceAiService} (face models are not thread-safe). All database
 * access is serialized through a single lock because the shared SQLite
 * connection is not thread-safe either. Each file is processed independently;
 * errors are counted but do not abort the overall import.</p>
 */
public class ImportService implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ImportService.class.getName());

    /** JPEG quality for thumbnail encoding. */
    private static final float JPEG_QUALITY = 0.85f;

    /** Supported image file extensions (case-insensitive, without the leading dot). */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "bmp", "gif", "webp"
    );

    private final ImageDao imageDao;
    private final FaceDao faceDao;
    private final List<FaceAiService> faceAiServices;
    private final ConfigModel config;
    private final TransactionRunner transactionRunner;

    /**
     * Creates an import service that processes files sequentially through a
     * single {@link FaceAiService}.
     *
     * @param imageDao          DAO for the images and image_paths tables
     * @param faceDao           DAO for the faces table
     * @param faceAiService     face detection and embedding service
     * @param config            application configuration (detection thresholds, etc.)
     * @param transactionRunner runner for atomic multi-statement write units
     */
    public ImportService(ImageDao imageDao, FaceDao faceDao,
                         FaceAiService faceAiService, ConfigModel config,
                         TransactionRunner transactionRunner) {
        this(imageDao, faceDao, List.of(faceAiService), config, transactionRunner);
    }

    /**
     * Creates an import service that processes files in parallel. One worker
     * thread is started per configured import thread, and each worker uses its
     * own {@link FaceAiService} from the given list, so at most
     * {@code faceAiServices.size()} workers can run concurrently.
     *
     * @param imageDao          DAO for the images and image_paths tables
     * @param faceDao           DAO for the faces table
     * @param faceAiServices    one face service per parallel worker (must not be empty)
     * @param config            application configuration (detection thresholds, etc.)
     * @param transactionRunner runner for atomic multi-statement write units
     */
    public ImportService(ImageDao imageDao, FaceDao faceDao,
                         List<FaceAiService> faceAiServices, ConfigModel config,
                         TransactionRunner transactionRunner) {
        this.imageDao = Objects.requireNonNull(imageDao, "imageDao");
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
        if (faceAiServices == null || faceAiServices.isEmpty()) {
            throw new IllegalArgumentException("faceAiServices must not be empty");
        }
        this.faceAiServices = List.copyOf(faceAiServices);
        this.config = Objects.requireNonNull(config, "config");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
    }

    /**
     * Recursively imports all supported image files from the given folder.
     *
     * @param folder   the root directory to scan
     * @param progress listener for progress messages (may be {@code null})
     * @return a summary of the import operation
     * @throws IOException if the folder cannot be traversed
     */
    public ImportResult importFolder(Path folder, ProgressListener progress) throws IOException {
        return importFolder(folder, progress, () -> false);
    }

    /**
     * Recursively imports all supported image files from the given folder.
     *
     * <p>The import stops between files as soon as {@code cancelled} reports
     * {@code true}; files already processed remain in the database. When
     * multiple workers are active, the run may complete the files that were
     * already in flight, so the reported {@code processed} count can exceed
     * the number of files completed by the time cancellation is noticed.</p>
     *
     * @param folder    the root directory to scan
     * @param progress  listener for progress messages (may be {@code null})
     * @param cancelled supplier consulted before each file; when it returns
     *                  {@code true} the import stops (may be {@code null})
     * @return a summary of the import operation
     * @throws IOException if the folder cannot be traversed
     */
    public ImportResult importFolder(Path folder, ProgressListener progress,
                                     BooleanSupplier cancelled) throws IOException {
        List<Path> imageFiles = collectImageFiles(folder, cancelled);
        if (imageFiles.isEmpty()) {
            return new ImportResult(0, 0, 0, 0, 0, 0, 0,
                    cancelled != null && cancelled.getAsBoolean());
        }
        int total = imageFiles.size();

        int threads = Math.min(Math.max(1, config.getMaxImportThreads()), faceAiServices.size());

        int minBbox = config.getMinBoundingBoxSize();
        double minConfidence = config.getMinConfidence();
        int maxFacesPerImage = config.getMaxFacesPerImage();
        String criteriaJson = FaceDetectionUtils.buildCriteriaJson(minBbox, minConfidence, maxFacesPerImage);

        AtomicInteger nextFile = new AtomicInteger();
        AtomicInteger newImages = new AtomicInteger();
        AtomicInteger newPaths = new AtomicInteger();
        AtomicInteger newFaces = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger processed = new AtomicInteger();
        AtomicBoolean stopped = new AtomicBoolean();

        AtomicInteger workerIds = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable,
                    "import-worker-" + workerIds.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int w = 0; w < threads; w++) {
                final FaceAiService service = faceAiServices.get(w % faceAiServices.size());
                futures.add(pool.submit(() -> runWorker(service, imageFiles, total, criteriaJson,
                        minBbox, minConfidence, maxFacesPerImage, progress, cancelled,
                        nextFile, newImages, newPaths, newFaces, skipped, errors,
                        processed, stopped)));
            }
        } finally {
            pool.shutdown();
            awaitWorkerCompletion(futures);
        }

        boolean wasCancelled = stopped.get()
                || (cancelled != null && cancelled.getAsBoolean());
        return new ImportResult(total, newImages.get(), newPaths.get(), newFaces.get(),
                skipped.get(), errors.get(), processed.get(), wasCancelled);
    }

    /**
     * Single worker loop: pulls file indices from the shared counter until the
     * import is cancelled or all files are processed.
     */
    private void runWorker(FaceAiService service, List<Path> imageFiles, int total,
                           String criteriaJson, int minBbox, double minConfidence,
                           int maxFacesPerImage, ProgressListener progress,
                           BooleanSupplier cancelled, AtomicInteger nextFile,
                           AtomicInteger newImages, AtomicInteger newPaths,
                           AtomicInteger newFaces, AtomicInteger skipped,
                           AtomicInteger errors, AtomicInteger processed,
                           AtomicBoolean stopped) {
        for (int i = nextFile.getAndIncrement(); i < total; i = nextFile.getAndIncrement()) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                stopped.set(true);
                return;
            }
            Path file = imageFiles.get(i);
            int index = i + 1;
            reportProgress(progress, String.format(Locale.ROOT,
                    "Started %d/%d: %s", index, total, file.getFileName()));
            try {
                FileResult result = processFile(file, criteriaJson, minBbox, minConfidence,
                        maxFacesPerImage, service);
                if (result.newImage) {
                    newImages.incrementAndGet();
                }
                if (result.newPath) {
                    newPaths.incrementAndGet();
                }
                if (result.skipped) {
                    skipped.incrementAndGet();
                }
                newFaces.addAndGet(result.facesAdded);
                if (result.droppedFaces > 0) {
                    // Faces lost to a crop/embedding/encoding failure are
                    // surfaced as a file error so they are not invisible to
                    // the user, while the remaining faces are still stored.
                    errors.incrementAndGet();
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error processing file: " + file, e);
                errors.incrementAndGet();
            }
            processed.incrementAndGet();
            reportProgress(progress, String.format(Locale.ROOT,
                    "Completed %d/%d: %s", index, total, file.getFileName()));
        }
    }

    private static void reportProgress(ProgressListener progress, String message) {
        if (progress != null) {
            synchronized (progress) {
                progress.onProgress(message);
            }
        }
    }

    /**
     * Blocks until every worker future completes. Unbounded: {@code importFolder}
     * must not report completion while workers are still importing. An interruption
     * (JVM shutdown) stops the join and returns the current aggregate; a worker
     * that dies unexpectedly is logged and the remaining workers are still joined.
     */
    private static void awaitWorkerCompletion(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                LOG.log(Level.SEVERE, "Import worker failed unexpectedly", e.getCause());
            }
        }
    }

    /**
     * Processes a single image file: detect faces, store new records, record paths.
     */
    private FileResult processFile(Path file, String criteriaJson,
                                   int minBbox, double minConfidence, int maxFacesPerImage,
                                   FaceAiService service) throws Exception {

        String hash = HashUtils.hashFile(file);
        String absolutePath = file.toAbsolutePath().toString();

        // ---- Known-image branch: one serialized unit ----
        boolean knownImage;
        boolean needThumbnail;
        if (imageDao.exists(hash)) {
            List<String> existingPaths = imageDao.getPaths(hash);
            if (existingPaths.contains(absolutePath)) {
                return FileResult.skipped();
            }
            imageDao.addPath(hash, absolutePath);
            needThumbnail = !imageDao.hasThumbnail(hash);
            knownImage = true;
        } else {
            knownImage = false;
            needThumbnail = false;
        }
        if (knownImage) {
            if (needThumbnail) {
                generateThumbnailIfAbsent(hash, file);
            }
            return FileResult.newPath();
        }

        // ---- new-image branch: heavy work happens outside the DB ----
        BufferedImage image = ImageUtils.readImage(file);

        // Face detection runs the shared photo/frame pipeline: large images are
        // scaled down before detection, bounding boxes are mapped back to the
        // original coordinates, and each qualifying face is cropped, downscaled,
        // embedded and encoded exactly like video frames are.
        FaceDetectionUtils.DetectionResult detection = FaceDetectionUtils.detectFaces(hash, image,
                config.getMaxDetectionDimension(), minBbox, minConfidence,
                maxFacesPerImage, service, file.toString());
        List<FaceRecord> faceRecords = detection.faces();
        int facesAdded = faceRecords.size();

        // ---- One atomic commit unit (insert + path + faces) ----
        try {
            transactionRunner.inTransaction(() -> {
                imageDao.insert(hash, System.currentTimeMillis(), criteriaJson,
                        faceRecords.size());
                imageDao.addPath(hash, absolutePath);
                for (FaceRecord faceRecord : faceRecords) {
                    faceDao.insert(faceRecord);
                }
                return null;
            });
        } catch (SQLException e) {
            // Another worker imported the same content first: record only
            // the additional path for this file.
            if (!imageDao.exists(hash)) {
                throw e;
            }
            List<String> existingPaths = imageDao.getPaths(hash);
            if (!existingPaths.contains(absolutePath)) {
                imageDao.addPath(hash, absolutePath);
            }
            if (!imageDao.hasThumbnail(hash)) {
                generateThumbnailIfAbsent(hash, file);
            }
            return FileResult.newPath();
        }

        // Generate thumbnail for the full image (reusing the loaded image)
        generateThumbnailIfAbsent(hash, image);

        return FileResult.newImage(facesAdded, detection.droppedFaces());
    }

    /**
     * Generates a JPEG thumbnail for the image if one is not already stored.
     * Database lookups and writes are serialized by the synchronized
     * connection; image encoding happens outside the DB.
     *
     * @param hash  content hash of the image
     * @param image already-loaded image to derive the thumbnail from
     */
    private void generateThumbnailIfAbsent(String hash, BufferedImage image) throws Exception {
        if (thumbnailPresent(hash)) {
            return;
        }
        byte[] thumbJpg = encodeThumbnail(image);
        saveThumbnailIfAbsent(hash, thumbJpg);
    }

    /**
     * Generates a JPEG thumbnail for the image if one is not already stored.
     * Database lookups and writes are serialized by the synchronized
     * connection; decoding the image and encoding the thumbnail happen
     * outside the DB.
     *
     * @param hash content hash of the image
     * @param file path to the image file on disk
     */
    private void generateThumbnailIfAbsent(String hash, Path file) throws Exception {
        if (thumbnailPresent(hash)) {
            return;
        }
        BufferedImage image = ImageUtils.readImage(file);
        byte[] thumbJpg = encodeThumbnail(image);
        saveThumbnailIfAbsent(hash, thumbJpg);
    }

    private boolean thumbnailPresent(String hash) throws SQLException {
        return imageDao.hasThumbnail(hash);
    }

    private void saveThumbnailIfAbsent(String hash, byte[] thumbJpg) throws SQLException {
        if (!imageDao.hasThumbnail(hash)) {
            imageDao.saveThumbnail(hash, thumbJpg);
        }
    }

    private byte[] encodeThumbnail(BufferedImage image) throws IOException {
        int thumbSize = config.getThumbnailSize();
        BufferedImage thumbnail = ImageUtils.downsize(image, thumbSize);
        return ImageUtils.toJpegBytes(thumbnail, JPEG_QUALITY);
    }

    // ---- File collection ----

    /**
     * Recursively collects all supported image files under {@code root}.
     *
     * <p>The walk terminates as soon as {@code cancelled} reports {@code true},
     * potentially returning a partial list.</p>
     *
     * @param root      the root directory to scan
     * @param cancelled supplier consulted before each file and directory; when
     *                  it returns {@code true} the scan stops (may be {@code null})
     */
    private List<Path> collectImageFiles(Path root, BooleanSupplier cancelled) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }
                if (attrs.isRegularFile() && isSupportedImage(file)) {
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
     * Returns {@code true} if the file has a supported image extension.
     */
    private static boolean isSupportedImage(Path file) {
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
     * Immutable summary of an import operation.
     *
     * @param totalFiles   total image files encountered
     * @param newImages    images newly added to the database
     * @param newPaths     additional paths recorded for already-known images
     * @param newFaces     face records created
     * @param skipped      files already fully known (image + path)
     * @param errors       files that failed processing or lost faces during detection
     * @param processed    files actually processed before the import stopped
     * @param wasCancelled true if the import was stopped via the cancellation supplier
     */
    public record ImportResult(
            int totalFiles,
            int newImages,
            int newPaths,
            int newFaces,
            int skipped,
            int errors,
            int processed,
            boolean wasCancelled
    ) {
    }

    /**
     * Callback for reporting import progress to the UI.
     */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String message);
    }

    // ---- Private inner type ----

    /**
     * Internal result of processing a single file.
     */
    private static final class FileResult {
        final boolean newImage;
        final boolean newPath;
        final boolean skipped;
        final int facesAdded;
        final int droppedFaces;

        private FileResult(boolean newImage, boolean newPath, boolean skipped,
                           int facesAdded, int droppedFaces) {
            this.newImage = newImage;
            this.newPath = newPath;
            this.skipped = skipped;
            this.facesAdded = facesAdded;
            this.droppedFaces = droppedFaces;
        }

        static FileResult newImage(int faces, int droppedFaces) {
            return new FileResult(true, false, false, faces, droppedFaces);
        }

        static FileResult newPath() {
            return new FileResult(false, true, false, 0, 0);
        }

        static FileResult skipped() {
            return new FileResult(false, false, true, 0, 0);
        }
    }
}
