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

    /**
     * Candidates fetched per name while looking for the first pair that was
     * not skipped this session. Large enough to step over the pairs a user
     * skips in a row, small enough to stay one query per name.
     */
    private static final int CANDIDATE_BATCH = 25;

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
        Suggestion best = null;
        for (NameRecord name : nameDao.findAll()) {
            SimilarityResult candidate = firstNotSkipped(name.id());
            if (candidate == null) {
                continue;
            }
            List<SimilarityResult> tagged = faceToNameService.findMostSimilarNamed(name.id(), 1);
            if (tagged.isEmpty()) {
                continue;
            }
            if (best == null || candidate.similarity() > best.nameSimilarity()) {
                FaceRecord taggedFace = tagged.get(0).faceRecord();
                best = new Suggestion(name, taggedFace, candidate.faceRecord(),
                        candidate.similarity(),
                        faceAiService.calcSimilarity(taggedFace.embedding(),
                                candidate.faceRecord().embedding()));
            }
        }
        return Optional.ofNullable(best);
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
     * Returns the name's best candidate that was not skipped this session.
     *
     * @param nameId id of the name whose candidates should be ranked
     * @return the first candidate not skipped, or {@code null} when the name
     *         has none left to offer
     * @throws SQLException on database error
     */
    private SimilarityResult firstNotSkipped(long nameId) throws SQLException {
        for (SimilarityResult candidate
                : faceToNameService.findUnnamedForName(nameId, CANDIDATE_BATCH)) {
            if (!skipped.contains(new MatchKey(candidate.faceRecord().id(), nameId))) {
                return candidate;
            }
        }
        return null;
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
