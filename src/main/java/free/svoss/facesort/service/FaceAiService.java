package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.tools.faceai.DetectedFace;
import free.svoss.tools.faceai.FaceAI;
import free.svoss.tools.faceai.FaceAIConfig;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

/**
 * Wraps the FaceAI library (face detection, embeddings, similarity) for the rest
 * of the application.
 *
 * <p>All heavy work is delegated to a {@link FaceAI} instance created from the
 * application {@link ConfigModel}. The engine is abstracted behind the package-private
 * {@link Engine} seam so behavior can be tested in isolation without downloading
 * FaceAI models or loading native libraries.</p>
 */
public class FaceAiService implements AutoCloseable {

    /** Dimension of FaceAI recognition embeddings. */
    static final int EMBEDDING_DIMENSION = 512;

    /** Compute device for FaceAI model inference. */
    private static final String DEVICE = "CPU";

    private final Engine engine;

    /**
     * Creates a service backed by a real FaceAI engine built from the given settings.
     *
     * @param config application settings; must not be null
     * @throws NullPointerException if {@code config} is null
     */
    public FaceAiService(ConfigModel config) {
        this(new FaceAiEngine(FaceAI.create(toFaceAIConfig(Objects.requireNonNull(config, "config")))));
    }

    /**
     * Creates a service backed by the given engine. Package-private test seam.
     *
     * @param engine engine implementation; must not be null
     */
    FaceAiService(Engine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Detects faces in the given image.
     *
     * @param image the image to analyze; must not be null
     * @return detected faces sorted by descending confidence
     * @throws IllegalArgumentException if {@code image} is null
     */
    public DetectedFace[] detectFaces(BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        return engine.detectFaces(image);
    }

    /**
     * Computes the recognition embedding for a face crop.
     *
     * @param faceCrop the cropped face image; must not be null
     * @return the embedding vector
     * @throws IllegalArgumentException if {@code faceCrop} is null
     */
    public float[] getEmbedding(BufferedImage faceCrop) {
        if (faceCrop == null) {
            throw new IllegalArgumentException("faceCrop must not be null");
        }
        return engine.getEmbedding(faceCrop);
    }

    /**
     * Computes the similarity between two embeddings.
     *
     * @param a first embedding; must not be null
     * @param b second embedding; must not be null
     * @return similarity in the range [0, 1]
     * @throws IllegalArgumentException if either argument is null
     */
    public double calcSimilarity(float[] a, float[] b) {
        if (a == null) {
            throw new IllegalArgumentException("a must not be null");
        }
        if (b == null) {
            throw new IllegalArgumentException("b must not be null");
        }
        return engine.calcSimilarity(a, b);
    }

    /**
     * Computes the average of the given embeddings (component-wise mean).
     *
     * @param embeddings list of embeddings; must not be null
     * @return the average embedding
     * @throws IllegalArgumentException if {@code embeddings} is null
     */
    public float[] calcAverage(List<float[]> embeddings) {
        if (embeddings == null) {
            throw new IllegalArgumentException("embeddings must not be null");
        }
        return engine.calcAverage(embeddings);
    }

    /**
     * Releases the underlying FaceAI resources.
     */
    @Override
    public void close() {
        engine.close();
    }

    /**
     * Maps application settings to a FaceAI configuration.
     *
     * <p>A blank or null cache directory is mapped to the empty string so FaceAI
     * falls back to its own default cache location ({@code $DJL_CACHE_DIR} or
     * {@code ~/.djl.ai/cache}).</p>
     *
     * @param config application settings; must not be null
     * @return the FaceAI configuration
     */
    static FaceAIConfig toFaceAIConfig(ConfigModel config) {
        String cacheDir = config.getFaceaiCacheDir();
        if (cacheDir == null || cacheDir.isBlank()) {
            cacheDir = "";
        }
        return FaceAIConfig.builder()
                .cacheDir(cacheDir)
                .detectionThreshold((float) config.getMinConfidence())
                .embeddingDimension(EMBEDDING_DIMENSION)
                .device(DEVICE)
                .l2NormalizeEmbeddings(true)
                .build();
    }

    /**
     * Abstraction over the FaceAI engine, kept package-private as a test seam.
     */
    interface Engine extends AutoCloseable {

        DetectedFace[] detectFaces(BufferedImage image);

        float[] getEmbedding(BufferedImage faceCrop);

        double calcSimilarity(float[] a, float[] b);

        float[] calcAverage(List<float[]> embeddings);

        @Override
        void close();
    }

    /**
     * Adapter that delegates engine calls to a real {@link FaceAI} instance.
     */
    private static final class FaceAiEngine implements Engine {

        private final FaceAI faceai;

        FaceAiEngine(FaceAI faceai) {
            this.faceai = Objects.requireNonNull(faceai, "faceai");
        }

        @Override
        public DetectedFace[] detectFaces(BufferedImage image) {
            return faceai.detectFaces(image);
        }

        @Override
        public float[] getEmbedding(BufferedImage faceCrop) {
            return faceai.getEmbedding(faceCrop);
        }

        @Override
        public double calcSimilarity(float[] a, float[] b) {
            return faceai.calcSimilarity(a, b);
        }

        @Override
        public float[] calcAverage(List<float[]> embeddings) {
            return faceai.calcAverage(embeddings);
        }

        @Override
        public void close() {
            faceai.close();
        }
    }
}