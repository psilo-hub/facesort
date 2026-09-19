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