package free.svoss.facesort.service;

import free.svoss.tools.faceai.DetectedFace;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reusable fake FaceAI engine for service tests.
 *
 * <p>{@link FaceAiService}'s engine seam is package-private, so tests in the
 * {@code free.svoss.facesort.service} package can inject this deterministic
 * double. Similarity is plain cosine similarity and averaging is a
 * component-wise mean, which keeps the math simple to assert.</p>
 */
class FakeFaceAiEngine implements FaceAiService.Engine {

    private DetectedFace[] faces = new DetectedFace[0];
    private float[] embedding = new float[]{1, 0, 0, 0, 0, 0, 0, 0};
    private boolean failEmbedding = false;
    private final AtomicInteger detectCalls = new AtomicInteger();

    /** Sets the faces reported for every detected image. */
    FakeFaceAiEngine withFaces(DetectedFace... faces) {
        this.faces = faces;
        return this;
    }

    /** Returns how many images were offered to face detection. */
    int detectCalls() {
        return detectCalls.get();
    }

    /** Sets the embedding returned for every face crop. */
    FakeFaceAiEngine withEmbedding(float[] embedding) {
        this.embedding = embedding;
        return this;
    }

    /** Makes every {@link #getEmbedding(BufferedImage)} call throw. */
    FakeFaceAiEngine withFailingEmbedding() {
        this.failEmbedding = true;
        return this;
    }

    @Override
    public DetectedFace[] detectFaces(BufferedImage image) {
        detectCalls.incrementAndGet();
        return faces;
    }

    @Override
    public float[] getEmbedding(BufferedImage faceCrop) {
        if (failEmbedding) {
            throw new IllegalStateException("embedding unavailable");
        }
        return embedding;
    }

    @Override
    public double calcSimilarity(float[] a, float[] b) {
        return cosineSimilarity(a, b);
    }

    /**
     * Cosine similarity used by the fake engines. Kept here (package-private)
     * so service tests share a single implementation of the same math the real
     * engine applies.
     */
    static double cosineSimilarity(float[] a, float[] b) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    @Override
    public float[] calcAverage(List<float[]> embeddings) {
        if (embeddings.isEmpty()) {
            return new float[0];
        }
        int dim = embeddings.get(0).length;
        float[] average = new float[dim];
        for (float[] e : embeddings) {
            for (int i = 0; i < dim; i++) {
                average[i] += e[i];
            }
        }
        for (int i = 0; i < dim; i++) {
            average[i] /= embeddings.size();
        }
        return average;
    }

    @Override
    public void close() {
        // nothing to release
    }
}