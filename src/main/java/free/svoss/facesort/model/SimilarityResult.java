package free.svoss.facesort.model;

import java.util.Objects;

/**
 * Result of comparing a face's embedding against a reference embedding.
 *
 * <p>Sorts in descending order of similarity so the most similar faces come
 * first when used with {@code Collections.sort} / stream sorting.
 */
public final class SimilarityResult implements Comparable<SimilarityResult> {

    private final FaceRecord faceRecord;
    private final double similarity;

    public SimilarityResult(FaceRecord faceRecord, double similarity) {
        this.faceRecord = Objects.requireNonNull(faceRecord, "faceRecord");
        this.similarity = similarity;
    }

    public FaceRecord faceRecord() {
        return faceRecord;
    }

    /** Similarity score in [0, 1] (higher is more similar). */
    public double similarity() {
        return similarity;
    }

    /**
     * Compares by similarity in descending order.
     */
    @Override
    public int compareTo(SimilarityResult o) {
        return Double.compare(o.similarity, this.similarity);
    }

    @Override
    public String toString() {
        return "SimilarityResult{faceId=" + faceRecord.id() + ", similarity=" + similarity + '}';
    }
}