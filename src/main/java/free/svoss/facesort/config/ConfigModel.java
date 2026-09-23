package free.svoss.facesort.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Serializable settings model for the Face Sort application.
 *
 * <p>The field names match the keys written to {@code config/facesort-config.json}.
 * All fields have defaults so the application works out of the box when the
 * config file is absent or partially filled in.</p>
 */
public class ConfigModel {

    public static final int DEFAULT_THUMBNAIL_SIZE = 256;
    public static final int DEFAULT_MAX_DETECTION_DIMENSION = 1600;
    public static final int DEFAULT_MAX_IMPORT_THREADS = 4;
    /** Hard upper bound for parallel import worker threads. */
    public static final int MAX_IMPORT_THREADS = 16;
    public static final String DEFAULT_DB_NAME = "facesort.db";
    /** Minimum similarity for adding a face to an existing name. */
    public static final double DEFAULT_MIN_NAME_SIMILARITY = 0.75;
    /** Maximum unnamed faces shown as candidates in the "Add faces to a name" tab. */
    public static final int DEFAULT_FACE_NAME_MAX_IMAGES = 30;
    /** Whether the automatic update check runs on application startup. */
    public static final boolean DEFAULT_UPDATE_CHECK_ENABLED = true;
    /** Language code for the UI, one of the codes shipped in {@code i18n/messages*.properties}. */
    public static final String DEFAULT_LANGUAGE = "en";

    // Lower bounds enforced by normalize() on load so hand-edited config files
    // cannot produce degenerate values that surface as runtime errors later.
    private static final int MIN_BOUNDING_BOX_SIZE = 1;
    private static final int MIN_MAX_FACES_PER_IMAGE = 1;
    private static final int MIN_MAX_DETECTION_DIMENSION = 16;
    private static final int MIN_THUMBNAIL_SIZE = 1;
    private static final int MIN_HNSW_M = 2;
    private static final int MIN_HNSW_EF = 1;
    private static final int MIN_KNN_K = 1;
    private static final int MIN_FACE_NAME_MAX_IMAGES = 1;

    private String lastImportFolder = "";

    // Face detection criteria
    @JsonProperty("minBoundingBoxSize")
    private int minBoundingBoxSize = 80;
    @JsonProperty("minConfidence")
    private double minConfidence = 0.8;
    @JsonProperty("maxFacesPerImage")
    private int maxFacesPerImage = 10;

    // Import performance
    @JsonProperty("maxDetectionDimension")
    private int maxDetectionDimension = DEFAULT_MAX_DETECTION_DIMENSION;

    // Clustering
    @JsonProperty("clusteringThreshold")
    private double clusteringThreshold = 0.5;
    @JsonProperty("hnswM")
    private int hnswM = 16;
    @JsonProperty("hnswEfConstruction")
    private int hnswEfConstruction = 200;
    @JsonProperty("hnswEfSearch")
    private int hnswEfSearch = 100;
    @JsonProperty("knnK")
    private int knnK = 20;

    // FaceAI model cache
    @JsonProperty("faceaiCacheDir")
    private String faceaiCacheDir;

    // App settings
    @JsonProperty("thumbnailSize")
    private int thumbnailSize = DEFAULT_THUMBNAIL_SIZE;
    @JsonProperty("maxImportThreads")
    private int maxImportThreads = DEFAULT_MAX_IMPORT_THREADS;
    @JsonProperty("dbName")
    private String dbName = DEFAULT_DB_NAME;
    @JsonProperty("minNameSimilarity")
    private double minNameSimilarity = DEFAULT_MIN_NAME_SIMILARITY;
    @JsonProperty("faceNameMaxImages")
    private int faceNameMaxImages = DEFAULT_FACE_NAME_MAX_IMAGES;

    // Startup behaviour
    @JsonProperty("updateCheckEnabled")
    private boolean updateCheckEnabled = DEFAULT_UPDATE_CHECK_ENABLED;

    // Internationalization
    @JsonProperty("language")
    private String language = DEFAULT_LANGUAGE;

    public ConfigModel() {
        // No-arg constructor required by Jackson for deserialization.
    }

    /**
     * Normalizes the values of a deserialized config (or a hand-edited one)
     * into ranges the rest of the application can rely on. Counts, dimensions
     * and HNSW parameters are clamped to sensible lower bounds, ratios and
     * similarities are clamped to [0, 1], and the import thread pool is capped
     * at {@link #MAX_IMPORT_THREADS}. The defaults are already in range, so a
     * fresh {@code ConfigModel} is left untouched.
     *
     * <p>The application's config file is only produced by this application and
     * its UI constrains every field, so the main concern is a config file that
     * was hand-edited (or written by a newer/or older build). Loading must not
     * let such values surface later as FaceAI library or HNSW index errors.</p>
     */
    public void normalize() {
        minBoundingBoxSize = clamp(minBoundingBoxSize, MIN_BOUNDING_BOX_SIZE, Integer.MAX_VALUE);
        minConfidence = clamp(minConfidence, 0.0, 1.0);
        maxFacesPerImage = clamp(maxFacesPerImage, MIN_MAX_FACES_PER_IMAGE, Integer.MAX_VALUE);
        maxDetectionDimension = clamp(maxDetectionDimension, MIN_MAX_DETECTION_DIMENSION, Integer.MAX_VALUE);
        clusteringThreshold = clamp(clusteringThreshold, 0.0, 1.0);
        hnswM = clamp(hnswM, MIN_HNSW_M, Integer.MAX_VALUE);
        hnswEfConstruction = clamp(hnswEfConstruction, MIN_HNSW_EF, Integer.MAX_VALUE);
        hnswEfSearch = clamp(hnswEfSearch, MIN_HNSW_EF, Integer.MAX_VALUE);
        knnK = clamp(knnK, MIN_KNN_K, Integer.MAX_VALUE);
        thumbnailSize = clamp(thumbnailSize, MIN_THUMBNAIL_SIZE, Integer.MAX_VALUE);
        maxImportThreads = clamp(maxImportThreads, 1, MAX_IMPORT_THREADS);
        minNameSimilarity = clamp(minNameSimilarity, 0.0, 1.0);
        faceNameMaxImages = clamp(faceNameMaxImages, MIN_FACE_NAME_MAX_IMAGES, Integer.MAX_VALUE);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public String getLastImportFolder() {
        return lastImportFolder;
    }

    public void setLastImportFolder(String lastImportFolder) {
        this.lastImportFolder = lastImportFolder;
    }

    public int getMinBoundingBoxSize() {
        return minBoundingBoxSize;
    }

    public void setMinBoundingBoxSize(int minBoundingBoxSize) {
        this.minBoundingBoxSize = minBoundingBoxSize;
    }

    public double getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    public int getMaxFacesPerImage() {
        return maxFacesPerImage;
    }

    public void setMaxFacesPerImage(int maxFacesPerImage) {
        this.maxFacesPerImage = maxFacesPerImage;
    }

    public int getMaxDetectionDimension() {
        return maxDetectionDimension;
    }

    public void setMaxDetectionDimension(int maxDetectionDimension) {
        this.maxDetectionDimension = maxDetectionDimension;
    }

    public double getClusteringThreshold() {
        return clusteringThreshold;
    }

    public void setClusteringThreshold(double clusteringThreshold) {
        this.clusteringThreshold = clusteringThreshold;
    }

    public int getHnswM() {
        return hnswM;
    }

    public void setHnswM(int hnswM) {
        this.hnswM = hnswM;
    }

    public int getHnswEfConstruction() {
        return hnswEfConstruction;
    }

    public void setHnswEfConstruction(int hnswEfConstruction) {
        this.hnswEfConstruction = hnswEfConstruction;
    }

    public int getHnswEfSearch() {
        return hnswEfSearch;
    }

    public void setHnswEfSearch(int hnswEfSearch) {
        this.hnswEfSearch = hnswEfSearch;
    }

    public int getKnnK() {
        return knnK;
    }

    public void setKnnK(int knnK) {
        this.knnK = knnK;
    }

    public String getFaceaiCacheDir() {
        return faceaiCacheDir;
    }

    public void setFaceaiCacheDir(String faceaiCacheDir) {
        this.faceaiCacheDir = faceaiCacheDir;
    }

    public int getThumbnailSize() {
        return thumbnailSize;
    }

    public void setThumbnailSize(int thumbnailSize) {
        this.thumbnailSize = thumbnailSize;
    }

    public int getMaxImportThreads() {
        return maxImportThreads;
    }

    public void setMaxImportThreads(int maxImportThreads) {
        this.maxImportThreads = maxImportThreads;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public double getMinNameSimilarity() {
        return minNameSimilarity;
    }

    public void setMinNameSimilarity(double minNameSimilarity) {
        this.minNameSimilarity = minNameSimilarity;
    }

    public int getFaceNameMaxImages() {
        return faceNameMaxImages;
    }

    public void setFaceNameMaxImages(int faceNameMaxImages) {
        this.faceNameMaxImages = faceNameMaxImages;
    }

    public boolean isUpdateCheckEnabled() {
        return updateCheckEnabled;
    }

    public void setUpdateCheckEnabled(boolean updateCheckEnabled) {
        this.updateCheckEnabled = updateCheckEnabled;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }
}