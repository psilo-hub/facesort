package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.util.HashUtils;
import free.svoss.facesort.util.ImageUtils;

import free.svoss.tools.faceai.DetectedFace;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orchestrates the import of image files into the Face Sort database.
 *
 * <p>For each image file the service:
 * <ol>
 *   <li>Computes a SHA-256 content hash.</li>
 *   <li>If the image is new, runs face detection and stores qualifying faces.</li>
 *   <li>If the image exists but from a new path, records the additional path.</li>
 *   <li>Generates a thumbnail if one is not already stored.</li>
 * </ol>
 *
 * <p>Each file is processed independently; errors are counted but do not abort
 * the overall import.</p>
 */
public class ImportService implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ImportService.class.getName());

    /** Maximum dimension (width or height) for face sub-image thumbnails. */
    private static final int SUB_IMAGE_MAX_DIM = 160;

    /** JPEG quality for face sub-image encoding. */
    private static final float SUB_IMAGE_JPEG_QUALITY = 0.85f;

    /** Supported image file extensions (case-insensitive, without the leading dot). */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "bmp", "gif", "webp"
    );

    private final ImageDao imageDao;
    private final FaceDao faceDao;
    private final FaceAiService faceAiService;
    private final ConfigModel config;

    /**
     * Creates an import service.
     *
     * @param imageDao      DAO for the images and image_paths tables
     * @param faceDao       DAO for the faces table
     * @param faceAiService face detection and embedding service
     * @param config        application configuration (detection thresholds, etc.)
     */
    public ImportService(ImageDao imageDao, FaceDao faceDao,
                         FaceAiService faceAiService, ConfigModel config) {
        this.imageDao = imageDao;
        this.faceDao = faceDao;
        this.faceAiService = faceAiService;
        this.config = config;
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
     * {@code true}; files already processed remain in the database.</p>
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
        int newImages = 0;
        int newPaths = 0;
        int newFaces = 0;
        int skipped = 0;
        int errors = 0;
        int processed = 0;

        List<Path> imageFiles = collectImageFiles(folder, cancelled);
        int total = imageFiles.size();

        int minBbox = config.getMinBoundingBoxSize();
        double minConfidence = config.getMinConfidence();
        int maxFacesPerImage = config.getMaxFacesPerImage();
        String criteriaJson = buildCriteriaJson(minBbox, minConfidence, maxFacesPerImage);

        for (int i = 0; i < total; i++) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                break;
            }
            Path file = imageFiles.get(i);

            if (progress != null) {
                progress.onProgress(String.format(Locale.ROOT,
                        "Processing %d/%d: %s", i + 1, total, file.getFileName()));
            }

            try {
                FileResult result = processFile(file, criteriaJson,
                        minBbox, minConfidence, maxFacesPerImage);
                if (result.newImage) {
                    newImages++;
                }
                if (result.newPath) {
                    newPaths++;
                }
                if (result.skipped) {
                    skipped++;
                }
                newFaces += result.facesAdded;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error processing file: " + file, e);
                errors++;
            }
            processed++;
        }

        boolean wasCancelled = cancelled != null && cancelled.getAsBoolean();
        return new ImportResult(total, newImages, newPaths, newFaces, skipped, errors,
                processed, wasCancelled);
    }

    /**
     * Processes a single image file: detect faces, store new records, record paths.
     */
    private FileResult processFile(Path file, String criteriaJson,
                                   int minBbox, double minConfidence, int maxFacesPerImage)
            throws Exception {

        String hash = HashUtils.hashFile(file);
        String absolutePath = file.toAbsolutePath().toString();

        // Check if this image hash is already known
        if (imageDao.exists(hash)) {
            List<String> existingPaths = imageDao.getPaths(hash);
            if (existingPaths.contains(absolutePath)) {
                // Image + path already known — skip entirely
                return FileResult.skipped();
            }
            // Known image, new path — record it and ensure thumbnail exists
            imageDao.addPath(hash, absolutePath);
            generateThumbnailIfAbsent(hash, file);
            return FileResult.newPath();
        }

        // New image: read, detect, store
        BufferedImage image = ImageUtils.readImage(file);
        DetectedFace[] allFaces = faceAiService.detectFaces(image);

        // Filter faces by criteria
        List<DetectedFace> qualifying = Arrays.stream(allFaces)
                .filter(f -> f.width() >= minBbox && f.height() >= minBbox)
                .filter(f -> f.confidence() >= minConfidence)
                .limit(maxFacesPerImage)
                .toList();

        // Insert image row (detection_ts = now, face_count = qualifying size)
        imageDao.insert(hash, System.currentTimeMillis(), criteriaJson, qualifying.size());
        imageDao.addPath(hash, absolutePath);

        // Process each qualifying face
        int facesAdded = 0;
        for (DetectedFace face : qualifying) {
            try {
                BufferedImage faceCrop = face.crop(image);
                float[] embedding = faceAiService.getEmbedding(faceCrop);

                // Downsize the crop and encode as JPEG for storage
                BufferedImage downsized = ImageUtils.downsize(faceCrop, SUB_IMAGE_MAX_DIM);
                byte[] subImageJpg = ImageUtils.toJpegBytes(downsized, SUB_IMAGE_JPEG_QUALITY);

                FaceRecord faceRecord = new FaceRecord(
                        0, hash,
                        face.x(), face.y(), face.width(), face.height(),
                        face.confidence(), embedding, subImageJpg, null);
                faceDao.insert(faceRecord);
                facesAdded++;
            } catch (Exception e) {
                LOG.log(Level.WARNING,
                        "Error processing detected face in " + file, e);
            }
        }

        // Generate thumbnail for the full image
        generateThumbnailIfAbsent(hash, file);

        return FileResult.newImage(facesAdded);
    }

    /**
     * Generates a JPEG thumbnail for the image if one is not already stored.
     *
     * @param hash content hash of the image
     * @param file path to the image file on disk
     */
    private void generateThumbnailIfAbsent(String hash, Path file) throws Exception {
        if (imageDao.hasThumbnail(hash)) {
            return;
        }
        BufferedImage image = ImageUtils.readImage(file);
        int thumbSize = config.getThumbnailSize();
        BufferedImage thumbnail = ImageUtils.downsize(image, thumbSize);
        byte[] thumbJpg = ImageUtils.toJpegBytes(thumbnail, 0.85f);
        imageDao.saveThumbnail(hash, thumbJpg);
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

    /**
     * Builds a compact JSON string recording the detection criteria used
     * for this import batch.
     */
    private static String buildCriteriaJson(int minBbox, double minConfidence, int maxFaces) {
        return String.format(Locale.ROOT,
                "{\"minBbox\":%d,\"minConfidence\":%.2f,\"maxFacesPerImage\":%d}",
                minBbox, minConfidence, maxFaces);
    }

    @Override
    public void close() throws Exception {
        faceAiService.close();
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
     * @param errors       files that failed processing
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

        private FileResult(boolean newImage, boolean newPath, boolean skipped, int facesAdded) {
            this.newImage = newImage;
            this.newPath = newPath;
            this.skipped = skipped;
            this.facesAdded = facesAdded;
        }

        static FileResult newImage(int faces) {
            return new FileResult(true, false, false, faces);
        }

        static FileResult newPath() {
            return new FileResult(false, true, false, 0);
        }

        static FileResult skipped() {
            return new FileResult(false, false, true, 0);
        }
    }
}
