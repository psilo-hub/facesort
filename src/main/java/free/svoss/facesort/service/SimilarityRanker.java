package free.svoss.facesort.service;

import com.github.jelmerk.hnswlib.core.DistanceFunctions;
import com.github.jelmerk.hnswlib.core.SearchResult;
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex;
import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.config.ConfigStore;
import free.svoss.facesort.model.FaceRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class SimilarityRanker {
    private static final double EPSILON = 1e-6;

    public record Candidate(FaceRecord face, double similarity) {
    }

    public static List<Candidate> rankBySimilarity(FaceRecord reference,
                                                    List<FaceRecord> candidates,
                                                    int limit,
                                                    ConfigStore configStore) {
        if (limit <= 0) {
            return List.of();
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        float[] refVec = reference.embedding();
        if (refVec == null || refVec.length == 0) {
            return List.of();
        }
        int dims = refVec.length;
        List<FaceRecord> valid = new ArrayList<>(candidates.size());
        for (FaceRecord c : candidates) {
            float[] v = c.embedding();
            if (v != null && v.length == dims) {
                valid.add(c);
            }
        }
        if (valid.isEmpty()) {
            return List.of();
        }

        ConfigModel config = Objects.requireNonNull(configStore).get();
        HnswIndex<Long, float[], ClusteringService.SimilarityEmbeddingItem, Float> index = HnswIndex
                .<float[], Float>newBuilder(dims, DistanceFunctions.FLOAT_COSINE_DISTANCE, valid.size())
                .withM(config.hnswM())
                .withEfConstruction(config.hnswEfConstruction())
                .withEf(Math.max(1, Math.min(limit * 2 + 10, config.hnswEfSearch())))
                .build();
        for (int i = 0; i < valid.size(); i++) {
            FaceRecord c = valid.get(i);
            index.add(new ClusteringService.SimilarityEmbeddingItem(c.id(), c.embedding()));
        }

        List<SearchResult<ClusteringService.SimilarityEmbeddingItem, Float>> nearest = index.findNearest(refVec, limit);
        List<Candidate> results = new ArrayList<>(nearest.size());
        for (SearchResult<ClusteringService.SimilarityEmbeddingItem, Float> r : nearest) {
            long id = r.item().id();
            for (FaceRecord c : valid) {
                if (c.id() == id) {
                    double sim = 1.0 - r.distance().doubleValue();
                    if (sim >= -EPSILON) {
                        results.add(new Candidate(c, Math.max(0.0, sim)));
                    }
                    break;
                }
            }
        }
        results.sort(Comparator.comparingDouble(Candidate::similarity).reversed());
        return results.size() <= limit ? results : new ArrayList<>(results.subList(0, limit));
    }

    public static List<Candidate> rankBySimilarityLinear(FaceRecord reference,
                                                          List<FaceRecord> candidates,
                                                          int limit) {
        if (limit <= 0) {
            return List.of();
        }
        float[] refVec = reference.embedding();
        if (refVec == null || refVec.length == 0) {
            return List.of();
        }
        int dims = refVec.length;
        List<Candidate> results = new ArrayList<>(candidates.size());
        FaceAiService faceAi = null;
        // Use FaceAiService if available via reflection not easy; better compute directly
        // but easier: compute cosine similarity directly
        for (FaceRecord c : candidates) {
            float[] v = c.embedding();
            if (v == null || v.length != dims) {
                continue;
            }
            if (c.id() == reference.id()) {
                continue;
            }
            double sim = cosineSimilarity(refVec, v);
            if (sim > 0.0 || Math.abs(sim) < 1.0) {
                // keep
            }
            results.add(new Candidate(c, sim));
        }
        results.sort(Comparator.comparingDouble(Candidate::similarity).reversed());
        return results.size() <= limit ? results : new ArrayList<>(results.subList(0, limit));
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dot += (double) a[i] * (double) b[i];
            na += (double) a[i] * (double) a[i];
            nb += (double) b[i] * (double) b[i];
        }
        if (na <= 0.0 || nb <= 0.0) {
            return 0.0;
        }
        double sim = dot / (Math.sqrt(na) * Math.sqrt(nb));
        if (Double.isNaN(sim)) {
            return 0.0;
        }
        if (sim < 0.0) {
            sim = 0.0;
        }
        if (sim > 1.0) {
            sim = 1.0;
        }
        return sim;
    }
}
