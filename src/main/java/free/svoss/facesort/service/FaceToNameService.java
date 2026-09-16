package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.model.SimilarityResult;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Implements the "Put a face to a name" flow (IMPLEMENTATION_PLAN.md section 6.5).
 *
 * <p>Given a name selected by the user, the service ranks all unnamed faces by
 * similarity to the average embedding of the faces already tagged with that
 * name, so the user can batch-tag the most similar ones without searching.</p>
 */
public class FaceToNameService {

    private final FaceAiService faceAiService;
    private final FaceDao faceDao;
    private final NameDao nameDao;
    private final ConfigModel config;

    /**
     * Creates the service.
     *
     * @param faceAiService engine for embedding math; must not be null
     * @param faceDao       data access for faces; must not be null
     * @param nameDao       data access for names; must not be null
     * @param config        application settings; must not be null
     */
    public FaceToNameService(FaceAiService faceAiService, FaceDao faceDao, NameDao nameDao,
                             ConfigModel config) {
        this.faceAiService = Objects.requireNonNull(faceAiService, "faceAiService");
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
        this.nameDao = Objects.requireNonNull(nameDao, "nameDao");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Returns all names, ordered alphabetically, for the dropdown list.
     *
     * @return all names in the database
     * @throws SQLException on database error
     */
    public List<NameRecord> getAllNames() throws SQLException {
        return nameDao.findAll();
    }

    /**
     * Returns the top {@code limit} unnamed faces most similar to the average
     * embedding of the given name, sorted by similarity descending.
     *
     * <p>The average embedding is computed over all faces currently tagged with
     * {@code nameId}. If the name has no faces (or none with embeddings), the
     * result is empty and no average is computed. Candidates whose similarity
     * falls below {@link ConfigModel#getMinNameSimilarity()} never qualify, so
     * faces that are not similar enough cannot be added to an existing name.</p>
     *
     * @param nameId id of the reference name
     * @param limit  maximum number of results; values &le; 0 yield an empty list
     * @return matching faces as {@link SimilarityResult}, descending by similarity
     * @throws SQLException on database error
     */
    public List<SimilarityResult> findUnnamedForName(long nameId, int limit) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }

        List<FaceRecord> namedFaces = faceDao.findByNameId(nameId);
        if (namedFaces.isEmpty()) {
            return List.of();
        }

        List<float[]> embeddings = new ArrayList<>(namedFaces.size());
        for (FaceRecord face : namedFaces) {
            embeddings.add(face.embedding());
        }
        float[] average = faceAiService.calcAverage(embeddings);
        double cutoff = config.getMinNameSimilarity();

        List<SimilarityResult> results = new ArrayList<>();
        for (FaceRecord candidate : faceDao.findUnnamed()) {
            double similarity = faceAiService.calcSimilarity(average, candidate.embedding());
            if (similarity >= cutoff) {
                results.add(new SimilarityResult(candidate, similarity));
            }
        }

        // SimilarityResult orders descending by similarity.
        Collections.sort(results);

        return results.size() <= limit ? results : new ArrayList<>(results.subList(0, limit));
    }

    /**
     * Returns the top {@code limit} faces already tagged with the given name
     * that are most similar to the name's average embedding, sorted by
     * descending similarity.
     *
     * <p>The average embedding is computed over all faces currently tagged with
     * {@code nameId}; each of those faces is then scored against that average.
     * If the name has no faces, the result is empty.</p>
     *
     * @param nameId id of the name whose tagged faces should be ranked
     * @param limit  maximum number of results; values &le; 0 yield an empty list
     * @return the name's tagged faces as {@link SimilarityResult}, descending by similarity
     * @throws SQLException on database error
     */
    public List<SimilarityResult> findMostSimilarNamed(long nameId, int limit) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }

        List<FaceRecord> namedFaces = faceDao.findByNameId(nameId);
        if (namedFaces.isEmpty()) {
            return List.of();
        }

        List<float[]> embeddings = new ArrayList<>(namedFaces.size());
        for (FaceRecord face : namedFaces) {
            embeddings.add(face.embedding());
        }
        float[] average = faceAiService.calcAverage(embeddings);

        List<SimilarityResult> results = new ArrayList<>();
        for (FaceRecord face : namedFaces) {
            results.add(new SimilarityResult(face,
                    faceAiService.calcSimilarity(average, face.embedding())));
        }

        // SimilarityResult orders descending by similarity.
        Collections.sort(results);

        return results.size() <= limit ? results : new ArrayList<>(results.subList(0, limit));
    }

    /**
     * Tags multiple faces with the given name.
     *
     * @param faceIds ids of the faces to tag; must not be null
     * @param nameId  id of the name to assign
     * @throws SQLException on database error
     */
    public void tagFaces(List<Long> faceIds, long nameId) throws SQLException {
        Objects.requireNonNull(faceIds, "faceIds");
        for (Long faceId : faceIds) {
            faceDao.assignName(faceId, nameId);
        }
    }
}