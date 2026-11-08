package free.svoss.facesort.service;

import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.db.NotMatchDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.model.SimilarityResult;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Backs the "Most similar" tab: offers the single (name, unnamed face) pair
 * with the highest similarity in the whole library, so the user can decide
 * whether the unnamed face is that person.
 *
 * <p>Each decision changes what is offered next. "Same person" tags the face,
 * which takes it out of the unnamed pool. "Not the same person" persists the
 * rejection in the {@code not_matches} table, which stops both naming tabs
 * from offering the combination again. "Skip" hides only the pair for the
 * current session, so the user is never forced to decide a pair right now.</p>
 */
public class MostSimilarService {

    private final FaceToNameService faceToNameService;
    private final FaceAiService faceAiService;
    private final NameDao nameDao;
    private final NotMatchDao notMatchDao;
    private final Set<MatchKey> skipped =
            Collections.synchronizedSet(new HashSet<>());

    /**
     * Creates the service.
     *
     * @param faceToNameService ranking of candidates and tagging; must not be null
     * @param faceAiService     engine for embedding math; must not be null
     * @param nameDao           data access for names; must not be null
     * @param notMatchDao       data access for rejected face/name combinations; must not be null
     */
    public MostSimilarService(FaceToNameService faceToNameService, FaceAiService faceAiService,
                              NameDao nameDao, NotMatchDao notMatchDao) {
        this.faceToNameService = Objects.requireNonNull(faceToNameService, "faceToNameService");
        this.faceAiService = Objects.requireNonNull(faceAiService, "faceAiService");
        this.nameDao = Objects.requireNonNull(nameDao, "nameDao");
        this.notMatchDao = Objects.requireNonNull(notMatchDao, "notMatchDao");
    }

    /**
     * Returns the most similar (name, unnamed face) pair of the whole library,
     * or empty when nothing is left to decide: no names, no unnamed faces, or
     * every combination rejected or skipped.
     *
     * <p>For each name the pair is the face closest to the name's average
     * embedding paired with its best remaining candidate; across names the
     * pair with the highest similarity wins. Candidates below the configured
     * {@code minNameSimilarity} and candidates the user rejected for that name
     * never qualify, and pairs skipped this session are stepped over.</p>
     *
     * @return the best pair still on offer, or empty when there is none
     * @throws SQLException on database error
     */
    public Optional<Suggestion> findBestSuggestion() throws SQLException {
        List<NameRecord> names = nameDao.findAll();
        if (names.isEmpty()) {
            return Optional.empty();
        }
        List<Long> nameIds = names.stream().map(NameRecord::id).toList();
        Map<Long, float[]> averages = faceToNameService.averageEmbeddingsFor(nameIds);
        Map<Long, Set<Long>> rejected = notMatchDao.findFaceIdsByNameIds(nameIds);
        List<FaceRecord> unnamed = faceToNameService.findUnnamedCandidates();
        double cutoff = faceToNameService.minNameSimilarity();

        NameRecord bestName = null;
        SimilarityResult bestCandidate = null;
        for (NameRecord name : names) {
            float[] average = averages.get(name.id());
            if (average == null) {
                continue;
            }
            SimilarityResult candidate = bestNotSkipped(name.id(), average, unnamed,
                    rejected.getOrDefault(name.id(), Set.of()), cutoff);
            if (candidate == null) {
                continue;
            }
            if (bestCandidate == null || candidate.similarity() > bestCandidate.similarity()) {
                bestName = name;
                bestCandidate = candidate;
                if (bestCandidate.similarity() >= 0.99) {
                    break;
                }
            }
        }
        if (bestCandidate == null) {
            return Optional.empty();
        }

        List<SimilarityResult> tagged = faceToNameService.findMostSimilarNamedSlim(bestName.id(), 1);
        if (tagged.isEmpty()) {
            return Optional.empty();
        }
        FaceRecord taggedFace = tagged.get(0).faceRecord();
        double faceSim = faceAiService.calcSimilarity(taggedFace.embedding(),
                bestCandidate.faceRecord().embedding());
        return hydrate(new Suggestion(bestName, taggedFace, bestCandidate.faceRecord(),
                bestCandidate.similarity(), faceSim));
    }

    /**
     * Loads the full rows of the two faces that will be shown, so the cards
     * still receive their thumbnail crops after a slim scan.
     *
     * @param suggestion the pair picked from slim rows, or {@code null}
     * @return the pair with hydrated faces, or empty when a face disappeared
     *         while the scan was running
     * @throws SQLException on database error
     */
    private Optional<Suggestion> hydrate(Suggestion suggestion) throws SQLException {
        if (suggestion == null) {
            return Optional.empty();
        }
        Optional<FaceRecord> tagged = faceToNameService.findFaceById(suggestion.tagged().id());
        Optional<FaceRecord> candidate = faceToNameService.findFaceById(suggestion.candidate().id());
        if (tagged.isEmpty() || candidate.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Suggestion(suggestion.name(), tagged.get(), candidate.get(),
                suggestion.nameSimilarity(), suggestion.faceSimilarity()));
    }

    /**
     * Applies the "same person" decision: the unnamed face is tagged with the
     * name, so it leaves the unnamed pool and the next pair is offered.
     *
     * @param faceId id of the unnamed face
     * @param nameId id of the name it was found to be
     * @throws SQLException on database error
     */
    public void markSamePerson(long faceId, long nameId) throws SQLException {
        faceToNameService.tagFace(faceId, nameId);
    }

    /**
     * Applies the "not the same person" decision: the rejection is stored so
     * neither naming tab ever offers that face for that name again. The face
     * stays a candidate for other names.
     *
     * @param faceId id of the unnamed face
     * @param nameId id of the name it was rejected for
     * @throws SQLException on database error
     */
    public void markDifferentPerson(long faceId, long nameId) throws SQLException {
        notMatchDao.insert(faceId, nameId);
    }

    /**
     * Applies the "skip" decision: the pair is hidden for the current session
     * only, nothing is written to the database, and a later run may offer it
     * again.
     *
     * @param faceId id of the unnamed face
     * @param nameId id of the name it was shown with
     */
    public void skip(long faceId, long nameId) {
        skipped.add(new MatchKey(faceId, nameId));
    }

    /**
     * Returns the name's best candidate that is not rejected and not skipped
     * this session, scoring the shared unnamed pool instead of reloading it.
     *
     * @param nameId   id of the name whose candidates should be ranked
     * @param average  the name's average embedding
     * @param unnamed  the shared unnamed candidate pool
     * @param rejected face ids the user rejected for this name
     * @param cutoff   minimum similarity a candidate needs to qualify
     * @return the best qualifying candidate, or {@code null} when the name has
     *         none left to offer
     */
    private SimilarityResult bestNotSkipped(long nameId, float[] average,
                                            List<FaceRecord> unnamed, Set<Long> rejected,
                                            double cutoff) {
        SimilarityResult best = null;
        for (FaceRecord face : unnamed) {
            if (rejected.contains(face.id())) {
                continue;
            }
            double similarity = faceAiService.calcSimilarity(average, face.embedding());
            if (similarity < cutoff) {
                continue;
            }
            if (skipped.contains(new MatchKey(face.id(), nameId))) {
                continue;
            }
            if (best == null || similarity > best.similarity()) {
                best = new SimilarityResult(face, similarity);
            }
        }
        return best;
    }

    /**
     * The pair shown by the "Most similar" tab.
     *
     * @param name           the person the unnamed face is suggested for
     * @param tagged         the face tagged with that name that is closest to
     *                       the name's average embedding
     * @param candidate      the unnamed face most similar to that average
     * @param nameSimilarity the candidate's similarity to the name's average
     *                       embedding, in [0, 1]; decides which pair is best
     * @param faceSimilarity the similarity between the two shown faces, in [0, 1]
     */
    public record Suggestion(NameRecord name, FaceRecord tagged, FaceRecord candidate,
                             double nameSimilarity, double faceSimilarity) {
    }

    /** An unnamed face paired with a name, as identified for session skips. */
    private record MatchKey(long faceId, long nameId) {
    }
}
