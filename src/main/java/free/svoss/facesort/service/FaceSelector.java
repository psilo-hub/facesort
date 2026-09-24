package free.svoss.facesort.service;

import free.svoss.facesort.model.FaceRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared embedding math behind the "representative face" concept.
 *
 * <p>"The face whose embedding is closest to the average embedding of a set of
 * faces" was previously reimplemented in the clustering, deduplication, view
 * and face-to-name services; this helper owns it once: the average of a set of
 * face embeddings ({@link #averageOf(List)}), the closest-to-average argmax
 * ({@link #mostSimilarTo(float[], List)}) and their combination
 * ({@link #representativeOf(List)}).</p>
 *
 * <p>Faces without a usable embedding cannot occur (a {@link FaceRecord}'s
 * embedding is non-null by construction), so every input face participates in
 * both the average and the pick.</p>
 */
public final class FaceSelector {

    private final FaceAiService faceAiService;

    public FaceSelector(FaceAiService faceAiService) {
        this.faceAiService = Objects.requireNonNull(faceAiService, "faceAiService");
    }

    /**
     * Computes the component-wise mean embedding of the given faces.
     *
     * @param faces the faces to average; must not be empty
     * @return the average embedding
     */
    public float[] averageOf(List<FaceRecord> faces) {
        List<float[]> embeddings = new ArrayList<>(faces.size());
        for (FaceRecord face : faces) {
            embeddings.add(face.embedding());
        }
        return faceAiService.calcAverage(embeddings);
    }

    /**
     * Picks the face whose embedding is most similar to the given average.
     * Ties keep the earliest face in the list, matching the behavior every
     * previous caller relied on.
     *
     * @param average the average embedding to compare against
     * @param faces   the faces to choose from; must not be empty
     * @return the closest face
     */
    public FaceRecord mostSimilarTo(float[] average, List<FaceRecord> faces) {
        FaceRecord best = faces.get(0);
        double bestScore = -1.0;
        for (FaceRecord face : faces) {
            double score = faceAiService.calcSimilarity(average, face.embedding());
            if (score > bestScore) {
                bestScore = score;
                best = face;
            }
        }
        return best;
    }

    /**
     * Picks the representative face of a set of faces: the face whose embedding
     * is closest to the average embedding of the whole set.
     *
     * @param faces the faces to choose from; must not be empty
     * @return the representative face
     */
    public FaceRecord representativeOf(List<FaceRecord> faces) {
        return mostSimilarTo(averageOf(faces), faces);
    }
}