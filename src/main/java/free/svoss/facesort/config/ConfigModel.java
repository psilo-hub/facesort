package free.svoss.facesort.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Immutable settings model for the Face Sort application.
 *
 * <p>The component names match the keys written to {@code config/facesort-config.json}.
 * All components have defaults so the application works out of the box when the
 * config file is absent or partially filled in. Unknown keys are ignored on
 * load, so a config written by a different build still starts the application
 * instead of aborting it.</p>
 *
 * <p>The record is immutable and shared across threads: a changed setting is a
 * new instance swapped in through a {@link ConfigStore}, so a reader either
 * sees the whole previous configuration or the whole new one and never a
 * half-applied mixture of both.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfigModel(
        String lastImportFolder,
        int minBoundingBoxSize,
        double minConfidence,
        int maxFacesPerImage,
        int maxDetectionDimension,
        float thumbnailQuality,
        int maxFramesPerVideo,
        int faceCropSize,
        double clusteringThreshold,
        int hnswM,
        int hnswEfConstruction,
        int hnswEfSearch,
        int knnK,
        String faceaiCacheDir,
        int thumbnailSize,
        int maxImportThreads,
        String dbName,
        double minNameSimilarity,
        int faceNameMaxImages,
        boolean updateCheckEnabled,
        String language
) {

    public static final int DEFAULT_THUMBNAIL_SIZE = 256;
    public static final int DEFAULT_MAX_DETECTION_DIMENSION = 1600;
    public static final int DEFAULT_MAX_IMPORT_THREADS = 4;
    /** Hard upper bound for parallel import worker threads. */
    public static final int MAX_IMPORT_THREADS = 16;
    /** JPEG quality for every stored thumbnail and face sub-image. */
    public static final float DEFAULT_THUMBNAIL_QUALITY = 0.85f;
    /** Hard upper bound on the number of frames extracted per video. */
    public static final int DEFAULT_MAX_FRAMES_PER_VIDEO = 120;
    /** Maximum width/height of the square crop stored per detected face. */
    public static final int DEFAULT_FACE_CROP_SIZE = 160;
    public static final String DEFAULT_DB_NAME = "facesort.db";
    /** Minimum similarity for adding a face to an existing name. */
    public static final double DEFAULT_MIN_NAME_SIMILARITY = 0.75;
    /** Maximum unnamed faces shown as candidates in the "Add faces to a name" tab. */
    public static final int DEFAULT_FACE_NAME_MAX_IMAGES = 30;
    /** Whether the automatic update check runs on application startup. */
    public static final boolean DEFAULT_UPDATE_CHECK_ENABLED = true;
    /** Language code for the UI, one of the codes shipped in {@code i18n/messages*.properties}. */
    public static final String DEFAULT_LANGUAGE = "en";
    /** No remembered import folder yet. */
    public static final String DEFAULT_LAST_IMPORT_FOLDER = "";

    public static final int DEFAULT_MIN_BOUNDING_BOX_SIZE = 80;
    public static final double DEFAULT_MIN_CONFIDENCE = 0.8;
    public static final int DEFAULT_MAX_FACES_PER_IMAGE = 10;
    public static final double DEFAULT_CLUSTERING_THRESHOLD = 0.5;
    public static final int DEFAULT_HNSW_M = 16;
    public static final int DEFAULT_HNSW_EF_CONSTRUCTION = 200;
    public static final int DEFAULT_HNSW_EF_SEARCH = 100;
    public static final int DEFAULT_KNN_K = 20;

    // Lower bounds enforced by normalized() on load so hand-edited config files
    // cannot produce degenerate values that surface as runtime errors later.
    private static final int MIN_BOUNDING_BOX_SIZE = 1;
    private static final int MIN_MAX_FACES_PER_IMAGE = 1;
    private static final int MIN_MAX_DETECTION_DIMENSION = 16;
    private static final int MIN_THUMBNAIL_SIZE = 1;
    private static final double MIN_THUMBNAIL_QUALITY = 0.1;
    private static final int MIN_MAX_FRAMES_PER_VIDEO = 1;
    private static final int MIN_FACE_CROP_SIZE = 1;
    private static final int MIN_HNSW_M = 2;
    private static final int MIN_HNSW_EF = 1;
    private static final int MIN_KNN_K = 1;
    private static final int MIN_FACE_NAME_MAX_IMAGES = 1;

    /**
     * Guards the components a config file may legitimately leave out or write
     * as {@code null}. A null would otherwise surface as a
     * {@link NullPointerException} far from the config file, for example when
     * the database path is resolved from {@code dbName}.
     */
    public ConfigModel {
        if (lastImportFolder == null) {
            lastImportFolder = DEFAULT_LAST_IMPORT_FOLDER;
        }
        if (dbName == null) {
            dbName = DEFAULT_DB_NAME;
        }
        if (language == null) {
            language = DEFAULT_LANGUAGE;
        }
    }

    /**
     * Returns the configuration the application starts with when no config file
     * is present: every component at its documented default.
     *
     * @return a config populated with the built-in defaults
     */
    public static ConfigModel defaults() {
        return new ConfigModel(
                DEFAULT_LAST_IMPORT_FOLDER,
                DEFAULT_MIN_BOUNDING_BOX_SIZE,
                DEFAULT_MIN_CONFIDENCE,
                DEFAULT_MAX_FACES_PER_IMAGE,
                DEFAULT_MAX_DETECTION_DIMENSION,
                DEFAULT_THUMBNAIL_QUALITY,
                DEFAULT_MAX_FRAMES_PER_VIDEO,
                DEFAULT_FACE_CROP_SIZE,
                DEFAULT_CLUSTERING_THRESHOLD,
                DEFAULT_HNSW_M,
                DEFAULT_HNSW_EF_CONSTRUCTION,
                DEFAULT_HNSW_EF_SEARCH,
                DEFAULT_KNN_K,
                null, // faceaiCacheDir: derived from the platform cache dir when unset
                DEFAULT_THUMBNAIL_SIZE,
                DEFAULT_MAX_IMPORT_THREADS,
                DEFAULT_DB_NAME,
                DEFAULT_MIN_NAME_SIMILARITY,
                DEFAULT_FACE_NAME_MAX_IMAGES,
                DEFAULT_UPDATE_CHECK_ENABLED,
                DEFAULT_LANGUAGE);
    }

    /**
     * Returns a copy of this config with the values of a deserialized config
     * (or a hand-edited one) clamped into ranges the rest of the application
     * can rely on. Counts, dimensions and HNSW parameters are clamped to
     * sensible lower bounds, ratios and similarities are clamped to [0, 1],
     * and the import thread pool is capped at {@link #MAX_IMPORT_THREADS}. The
     * defaults are already in range, so normalizing a fresh {@code ConfigModel}
     * returns an equal copy.
     *
     * <p>The application's config file is only produced by this application and
     * its UI constrains every field, so the main concern is a config file that
     * was hand-edited (or written by a newer/or older build). Loading must not
     * let such values surface later as FaceAI library or HNSW index errors.</p>
     *
     * @return this config with every component clamped into its valid range
     */
    public ConfigModel normalized() {
        return new ConfigModel(
                lastImportFolder,
                clamp(minBoundingBoxSize, MIN_BOUNDING_BOX_SIZE, Integer.MAX_VALUE),
                clamp(minConfidence, 0.0, 1.0),
                clamp(maxFacesPerImage, MIN_MAX_FACES_PER_IMAGE, Integer.MAX_VALUE),
                clamp(maxDetectionDimension, MIN_MAX_DETECTION_DIMENSION, Integer.MAX_VALUE),
                (float) clamp(thumbnailQuality, MIN_THUMBNAIL_QUALITY, 1.0),
                clamp(maxFramesPerVideo, MIN_MAX_FRAMES_PER_VIDEO, Integer.MAX_VALUE),
                clamp(faceCropSize, MIN_FACE_CROP_SIZE, Integer.MAX_VALUE),
                clamp(clusteringThreshold, 0.0, 1.0),
                clamp(hnswM, MIN_HNSW_M, Integer.MAX_VALUE),
                clamp(hnswEfConstruction, MIN_HNSW_EF, Integer.MAX_VALUE),
                clamp(hnswEfSearch, MIN_HNSW_EF, Integer.MAX_VALUE),
                clamp(knnK, MIN_KNN_K, Integer.MAX_VALUE),
                faceaiCacheDir,
                clamp(thumbnailSize, MIN_THUMBNAIL_SIZE, Integer.MAX_VALUE),
                clamp(maxImportThreads, 1, MAX_IMPORT_THREADS),
                dbName,
                clamp(minNameSimilarity, 0.0, 1.0),
                clamp(faceNameMaxImages, MIN_FACE_NAME_MAX_IMAGES, Integer.MAX_VALUE),
                updateCheckEnabled,
                language);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public ConfigModel withLastImportFolder(String lastImportFolder) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withMinBoundingBoxSize(int minBoundingBoxSize) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withMinConfidence(double minConfidence) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withMaxFacesPerImage(int maxFacesPerImage) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withMaxDetectionDimension(int maxDetectionDimension) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withThumbnailQuality(float thumbnailQuality) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withMaxFramesPerVideo(int maxFramesPerVideo) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withFaceCropSize(int faceCropSize) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withClusteringThreshold(double clusteringThreshold) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withHnswM(int hnswM) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withHnswEfConstruction(int hnswEfConstruction) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withHnswEfSearch(int hnswEfSearch) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withKnnK(int knnK) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withFaceaiCacheDir(String faceaiCacheDir) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withThumbnailSize(int thumbnailSize) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withMaxImportThreads(int maxImportThreads) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withDbName(String dbName) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withMinNameSimilarity(double minNameSimilarity) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withFaceNameMaxImages(int faceNameMaxImages) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withUpdateCheckEnabled(boolean updateCheckEnabled) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }

    public ConfigModel withLanguage(String language) {
        return new ConfigModel(lastImportFolder, minBoundingBoxSize, minConfidence,
                maxFacesPerImage, maxDetectionDimension, thumbnailQuality, maxFramesPerVideo,
                faceCropSize, clusteringThreshold, hnswM, hnswEfConstruction, hnswEfSearch,
                knnK, faceaiCacheDir, thumbnailSize, maxImportThreads, dbName,
                minNameSimilarity, faceNameMaxImages, updateCheckEnabled, language);
    }
}
