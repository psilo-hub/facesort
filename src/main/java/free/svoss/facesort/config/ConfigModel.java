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
        return new Builder().build();
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
        return toBuilder()
                .withMinBoundingBoxSize(clamp(minBoundingBoxSize, MIN_BOUNDING_BOX_SIZE, Integer.MAX_VALUE))
                .withMinConfidence(clamp(minConfidence, 0.0, 1.0))
                .withMaxFacesPerImage(clamp(maxFacesPerImage, MIN_MAX_FACES_PER_IMAGE, Integer.MAX_VALUE))
                .withMaxDetectionDimension(clamp(maxDetectionDimension, MIN_MAX_DETECTION_DIMENSION, Integer.MAX_VALUE))
                .withThumbnailQuality((float) clamp(thumbnailQuality, MIN_THUMBNAIL_QUALITY, 1.0))
                .withMaxFramesPerVideo(clamp(maxFramesPerVideo, MIN_MAX_FRAMES_PER_VIDEO, Integer.MAX_VALUE))
                .withFaceCropSize(clamp(faceCropSize, MIN_FACE_CROP_SIZE, Integer.MAX_VALUE))
                .withClusteringThreshold(clamp(clusteringThreshold, 0.0, 1.0))
                .withHnswM(clamp(hnswM, MIN_HNSW_M, Integer.MAX_VALUE))
                .withHnswEfConstruction(clamp(hnswEfConstruction, MIN_HNSW_EF, Integer.MAX_VALUE))
                .withHnswEfSearch(clamp(hnswEfSearch, MIN_HNSW_EF, Integer.MAX_VALUE))
                .withKnnK(clamp(knnK, MIN_KNN_K, Integer.MAX_VALUE))
                .withThumbnailSize(clamp(thumbnailSize, MIN_THUMBNAIL_SIZE, Integer.MAX_VALUE))
                .withMaxImportThreads(clamp(maxImportThreads, 1, MAX_IMPORT_THREADS))
                .withMinNameSimilarity(clamp(minNameSimilarity, 0.0, 1.0))
                .withFaceNameMaxImages(clamp(faceNameMaxImages, MIN_FACE_NAME_MAX_IMAGES, Integer.MAX_VALUE))
                .build();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Returns a builder initialized from this config's values, so a single
     * component can be changed without listing every field at the call site.
     *
     * @return a builder seeded with this config
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * A mutable builder for {@link ConfigModel} copies. Start from
     * {@link #toBuilder()} to alter one field of an existing config, or
     * {@code new Builder()} for the documented defaults.
     *
     * <p>Centralizing the field list here means adding a config field touches
     * the builder once instead of every {@code with*} method.</p>
     */
    public static final class Builder {
        private String lastImportFolder = DEFAULT_LAST_IMPORT_FOLDER;
        private int minBoundingBoxSize = DEFAULT_MIN_BOUNDING_BOX_SIZE;
        private double minConfidence = DEFAULT_MIN_CONFIDENCE;
        private int maxFacesPerImage = DEFAULT_MAX_FACES_PER_IMAGE;
        private int maxDetectionDimension = DEFAULT_MAX_DETECTION_DIMENSION;
        private float thumbnailQuality = DEFAULT_THUMBNAIL_QUALITY;
        private int maxFramesPerVideo = DEFAULT_MAX_FRAMES_PER_VIDEO;
        private int faceCropSize = DEFAULT_FACE_CROP_SIZE;
        private double clusteringThreshold = DEFAULT_CLUSTERING_THRESHOLD;
        private int hnswM = DEFAULT_HNSW_M;
        private int hnswEfConstruction = DEFAULT_HNSW_EF_CONSTRUCTION;
        private int hnswEfSearch = DEFAULT_HNSW_EF_SEARCH;
        private int knnK = DEFAULT_KNN_K;
        private String faceaiCacheDir;
        private int thumbnailSize = DEFAULT_THUMBNAIL_SIZE;
        private int maxImportThreads = DEFAULT_MAX_IMPORT_THREADS;
        private String dbName = DEFAULT_DB_NAME;
        private double minNameSimilarity = DEFAULT_MIN_NAME_SIMILARITY;
        private int faceNameMaxImages = DEFAULT_FACE_NAME_MAX_IMAGES;
        private boolean updateCheckEnabled = DEFAULT_UPDATE_CHECK_ENABLED;
        private String language = DEFAULT_LANGUAGE;

        private Builder() {
        }

        private Builder(ConfigModel source) {
            this.lastImportFolder = source.lastImportFolder;
            this.minBoundingBoxSize = source.minBoundingBoxSize;
            this.minConfidence = source.minConfidence;
            this.maxFacesPerImage = source.maxFacesPerImage;
            this.maxDetectionDimension = source.maxDetectionDimension;
            this.thumbnailQuality = source.thumbnailQuality;
            this.maxFramesPerVideo = source.maxFramesPerVideo;
            this.faceCropSize = source.faceCropSize;
            this.clusteringThreshold = source.clusteringThreshold;
            this.hnswM = source.hnswM;
            this.hnswEfConstruction = source.hnswEfConstruction;
            this.hnswEfSearch = source.hnswEfSearch;
            this.knnK = source.knnK;
            this.faceaiCacheDir = source.faceaiCacheDir;
            this.thumbnailSize = source.thumbnailSize;
            this.maxImportThreads = source.maxImportThreads;
            this.dbName = source.dbName;
            this.minNameSimilarity = source.minNameSimilarity;
            this.faceNameMaxImages = source.faceNameMaxImages;
            this.updateCheckEnabled = source.updateCheckEnabled;
            this.language = source.language;
        }

        public Builder withLastImportFolder(String lastImportFolder) {
            this.lastImportFolder = lastImportFolder;
            return this;
        }

        public Builder withMinBoundingBoxSize(int minBoundingBoxSize) {
            this.minBoundingBoxSize = minBoundingBoxSize;
            return this;
        }

        public Builder withMinConfidence(double minConfidence) {
            this.minConfidence = minConfidence;
            return this;
        }

        public Builder withMaxFacesPerImage(int maxFacesPerImage) {
            this.maxFacesPerImage = maxFacesPerImage;
            return this;
        }

        public Builder withMaxDetectionDimension(int maxDetectionDimension) {
            this.maxDetectionDimension = maxDetectionDimension;
            return this;
        }

        public Builder withThumbnailQuality(float thumbnailQuality) {
            this.thumbnailQuality = thumbnailQuality;
            return this;
        }

        public Builder withMaxFramesPerVideo(int maxFramesPerVideo) {
            this.maxFramesPerVideo = maxFramesPerVideo;
            return this;
        }

        public Builder withFaceCropSize(int faceCropSize) {
            this.faceCropSize = faceCropSize;
            return this;
        }

        public Builder withClusteringThreshold(double clusteringThreshold) {
            this.clusteringThreshold = clusteringThreshold;
            return this;
        }

        public Builder withHnswM(int hnswM) {
            this.hnswM = hnswM;
            return this;
        }

        public Builder withHnswEfConstruction(int hnswEfConstruction) {
            this.hnswEfConstruction = hnswEfConstruction;
            return this;
        }

        public Builder withHnswEfSearch(int hnswEfSearch) {
            this.hnswEfSearch = hnswEfSearch;
            return this;
        }

        public Builder withKnnK(int knnK) {
            this.knnK = knnK;
            return this;
        }

        public Builder withFaceaiCacheDir(String faceaiCacheDir) {
            this.faceaiCacheDir = faceaiCacheDir;
            return this;
        }

        public Builder withThumbnailSize(int thumbnailSize) {
            this.thumbnailSize = thumbnailSize;
            return this;
        }

        public Builder withMaxImportThreads(int maxImportThreads) {
            this.maxImportThreads = maxImportThreads;
            return this;
        }

        public Builder withDbName(String dbName) {
            this.dbName = dbName;
            return this;
        }

        public Builder withMinNameSimilarity(double minNameSimilarity) {
            this.minNameSimilarity = minNameSimilarity;
            return this;
        }

        public Builder withFaceNameMaxImages(int faceNameMaxImages) {
            this.faceNameMaxImages = faceNameMaxImages;
            return this;
        }

        public Builder withUpdateCheckEnabled(boolean updateCheckEnabled) {
            this.updateCheckEnabled = updateCheckEnabled;
            return this;
        }

        public Builder withLanguage(String language) {
            this.language = language;
            return this;
        }

        public ConfigModel build() {
            return new ConfigModel(
                    lastImportFolder,
                    minBoundingBoxSize,
                    minConfidence,
                    maxFacesPerImage,
                    maxDetectionDimension,
                    thumbnailQuality,
                    maxFramesPerVideo,
                    faceCropSize,
                    clusteringThreshold,
                    hnswM,
                    hnswEfConstruction,
                    hnswEfSearch,
                    knnK,
                    faceaiCacheDir,
                    thumbnailSize,
                    maxImportThreads,
                    dbName,
                    minNameSimilarity,
                    faceNameMaxImages,
                    updateCheckEnabled,
                    language);
        }
    }

    public ConfigModel withLastImportFolder(String lastImportFolder) {
        return toBuilder().withLastImportFolder(lastImportFolder).build();
    }

    public ConfigModel withMinBoundingBoxSize(int minBoundingBoxSize) {
        return toBuilder().withMinBoundingBoxSize(minBoundingBoxSize).build();
    }

    public ConfigModel withMinConfidence(double minConfidence) {
        return toBuilder().withMinConfidence(minConfidence).build();
    }

    public ConfigModel withMaxFacesPerImage(int maxFacesPerImage) {
        return toBuilder().withMaxFacesPerImage(maxFacesPerImage).build();
    }

    public ConfigModel withMaxDetectionDimension(int maxDetectionDimension) {
        return toBuilder().withMaxDetectionDimension(maxDetectionDimension).build();
    }

    public ConfigModel withThumbnailQuality(float thumbnailQuality) {
        return toBuilder().withThumbnailQuality(thumbnailQuality).build();
    }

    public ConfigModel withMaxFramesPerVideo(int maxFramesPerVideo) {
        return toBuilder().withMaxFramesPerVideo(maxFramesPerVideo).build();
    }

    public ConfigModel withFaceCropSize(int faceCropSize) {
        return toBuilder().withFaceCropSize(faceCropSize).build();
    }

    public ConfigModel withClusteringThreshold(double clusteringThreshold) {
        return toBuilder().withClusteringThreshold(clusteringThreshold).build();
    }

    public ConfigModel withHnswM(int hnswM) {
        return toBuilder().withHnswM(hnswM).build();
    }

    public ConfigModel withHnswEfConstruction(int hnswEfConstruction) {
        return toBuilder().withHnswEfConstruction(hnswEfConstruction).build();
    }

    public ConfigModel withHnswEfSearch(int hnswEfSearch) {
        return toBuilder().withHnswEfSearch(hnswEfSearch).build();
    }

    public ConfigModel withKnnK(int knnK) {
        return toBuilder().withKnnK(knnK).build();
    }

    public ConfigModel withFaceaiCacheDir(String faceaiCacheDir) {
        return toBuilder().withFaceaiCacheDir(faceaiCacheDir).build();
    }

    public ConfigModel withThumbnailSize(int thumbnailSize) {
        return toBuilder().withThumbnailSize(thumbnailSize).build();
    }

    public ConfigModel withMaxImportThreads(int maxImportThreads) {
        return toBuilder().withMaxImportThreads(maxImportThreads).build();
    }

    public ConfigModel withDbName(String dbName) {
        return toBuilder().withDbName(dbName).build();
    }

    public ConfigModel withMinNameSimilarity(double minNameSimilarity) {
        return toBuilder().withMinNameSimilarity(minNameSimilarity).build();
    }

    public ConfigModel withFaceNameMaxImages(int faceNameMaxImages) {
        return toBuilder().withFaceNameMaxImages(faceNameMaxImages).build();
    }

    public ConfigModel withUpdateCheckEnabled(boolean updateCheckEnabled) {
        return toBuilder().withUpdateCheckEnabled(updateCheckEnabled).build();
    }

    public ConfigModel withLanguage(String language) {
        return toBuilder().withLanguage(language).build();
    }
}
