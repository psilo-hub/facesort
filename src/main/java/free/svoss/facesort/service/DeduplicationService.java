package free.svoss.facesort.service;

import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.db.NotDupeDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Detects duplicate names by comparing their average face embeddings.
 *
 * <p>The workflow is entirely interactive: all name pairs are ranked by the
 * similarity of their average embeddings and presented one at a time. The user
 * decides for each pair whether the names are duplicates (merge), definitely
 * distinct (persisted to {@code not_dupes}), or to be skipped for this run.
 * There is no similarity cutoff; the session continues until the user stops
 * or no pairs are left.</p>
 */
public class DeduplicationService {

    private final FaceAiService faceAiService;
    private final FaceDao faceDao;
    private final NameDao nameDao;
    private final NotDupeDao notDupeDao;

    /** Candidate pairs sorted by descending similarity; built lazily. */
    private List<DupeCandidate> candidates;

    /** Index of the next unconsumed candidate. */
    private int nextIndex;

    /** Pairs explicitly skipped for this run (normalized keys). */
    private final Set<String> skippedThisRun = new HashSet<>();

    /**
     * Creates a deduplication service.
     *
     * @param faceAiService face similarity and embedding service
     * @param faceDao       DAO for the faces table
     * @param nameDao       DAO for the names table
     * @param notDupeDao    DAO for the not_dupes table
     */
    public DeduplicationService(FaceAiService faceAiService, FaceDao faceDao,
                                NameDao nameDao, NotDupeDao notDupeDao) {
        this.faceAiService = Objects.requireNonNull(faceAiService, "faceAiService");
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
        this.nameDao = Objects.requireNonNull(nameDao, "nameDao");
        this.notDupeDao = Objects.requireNonNull(notDupeDao, "notDupeDao");
    }

    /**
     * A pair of names that may be duplicates, with their similarity and a
     * representative face for each name to display to the user.
     *
     * @param nameIdA    id of the first name
     * @param nameIdB    id of the second name
     * @param similarity similarity between the two average embeddings
     * @param nameA      display name of the first name
     * @param nameB      display name of the second name
     * @param repFaceA   face closest to the first name's average embedding
     * @param repFaceB   face closest to the second name's average embedding
     */
    public record DupeCandidate(long nameIdA, long nameIdB, double similarity,
                                String nameA, String nameB,
                                FaceRecord repFaceA, FaceRecord repFaceB) {
    }

    /**
     * Returns the next most similar name pair not already marked as distinct
     * and not skipped this run.
     *
     * <p>Pairs are ranked lazily on the first call: all names are loaded, their
     * average embeddings computed, every pair scored, and pairs already in
     * {@code not_dupes} excluded. Names without faces (and thus without
     * embeddings) cannot be compared and are skipped entirely.</p>
     *
     * @return the next candidate pair, or {@link Optional#empty()} when no
     *         comparable pairs are left
     * @throws SQLException if the database cannot be queried
     */
    public Optional<DupeCandidate> nextPair() throws SQLException {
        if (candidates == null) {
            candidates = buildCandidates();
            nextIndex = 0;
        }
        while (nextIndex < candidates.size()) {
            DupeCandidate candidate = candidates.get(nextIndex++);
            if (!skippedThisRun.contains(key(candidate.nameIdA, candidate.nameIdB))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Merges two names: reassigns all faces of the eliminated name to the
     * surviving name and deletes the eliminated name row. Deleting the row
     * cascades to {@code not_dupes} entries involving it. The ranked candidate
     * list is rebuilt for the new name set.
     *
     * @param survivorNameId   the name that survives the merge
     * @param eliminatedNameId the name to eliminate and delete
     * @throws SQLException if the database cannot be updated
     */
    public void merge(long survivorNameId, long eliminatedNameId) throws SQLException {
        if (survivorNameId == eliminatedNameId) {
            throw new IllegalArgumentException("survivor and eliminated name must differ");
        }
        faceDao.reassignAll(eliminatedNameId, survivorNameId);
        nameDao.delete(eliminatedNameId);
        invalidate(eliminatedNameId);
    }

    /**
     * Persistently marks two names as not duplicates.
     *
     * @param nameIdA id of the first name
     * @param nameIdB id of the second name
     * @throws SQLException if the database cannot be updated
     */
    public void markNotDupes(long nameIdA, long nameIdB) throws SQLException {
        notDupeDao.insert(nameIdA, nameIdB);
        invalidate(-1);
    }

    /**
     * Skips a pair for the remainder of this session. The pair will not be
     * presented again even if {@link #nextPair()} would otherwise rank it.
     *
     * @param nameIdA id of the first name
     * @param nameIdB id of the second name
     */
    public void skip(long nameIdA, long nameIdB) {
        skippedThisRun.add(key(nameIdA, nameIdB));
    }

    /**
     * Clears all session state (skipped pairs and any cached ranking), so a
     * fresh ranking can begin.
     */
    public void reset() {
        skippedThisRun.clear();
        candidates = null;
        nextIndex = 0;
    }

    /**
     * Builds and sorts the ranked list of candidate pairs.
     */
    private List<DupeCandidate> buildCandidates() throws SQLException {
        List<NameWithAverage> names = loadNamesWithAverages();
        List<DupeCandidate> result = new ArrayList<>();

        for (int i = 0; i < names.size(); i++) {
            NameWithAverage a = names.get(i);
            for (int j = i + 1; j < names.size(); j++) {
                NameWithAverage b = names.get(j);
                if (notDupeDao.isNotDupe(a.record.id(), b.record.id())) {
                    continue;
                }
                double similarity = faceAiService.calcSimilarity(a.average, b.average);
                result.add(new DupeCandidate(a.record.id(), b.record.id(), similarity,
                        a.record.name(), b.record.name(), a.representative, b.representative));
            }
        }

        result.sort(Comparator.comparingDouble(DupeCandidate::similarity).reversed());
        return result;
    }

    /**
     * Loads every name together with its average embedding and a
     * representative face. Names without any faces or embeddings are skipped
     * because they cannot be compared.
     */
    private List<NameWithAverage> loadNamesWithAverages() throws SQLException {
        List<NameRecord> allNames = nameDao.findAll();
        List<NameWithAverage> result = new ArrayList<>();

        for (NameRecord record : allNames) {
            List<FaceRecord> faces = faceDao.findByNameId(record.id());
            if (faces.isEmpty()) {
                continue;
            }
            List<float[]> embeddings = new ArrayList<>(faces.size());
            for (FaceRecord face : faces) {
                embeddings.add(face.embedding());
            }
            float[] average = faceAiService.calcAverage(embeddings);

            // Pick the face closest to the name's average embedding (argmax).
            FaceRecord bestFace = faces.get(0);
            double bestSim = -1.0;
            for (FaceRecord face : faces) {
                double sim = faceAiService.calcSimilarity(average, face.embedding());
                if (sim > bestSim) {
                    bestSim = sim;
                    bestFace = face;
                }
            }
            result.add(new NameWithAverage(record, average, bestFace));
        }
        return result;
    }

    /**
     * Resets session state after a database change: drops the ranked list and
     * removes any skipped/state that referenced a deleted name. {@code deletedNameId}
     * is the name that was eliminated, or {@code -1} when no name was deleted.
     */
    private void invalidate(long deletedNameId) {
        candidates = null;
        nextIndex = 0;
        if (deletedNameId >= 0) {
            skippedThisRun.removeIf(k -> k.startsWith(deletedNameId + ":")
                    || k.endsWith(":" + deletedNameId));
        }
    }

    /**
     * Normalized pair key ({@code minId:maxId}) used for the in-memory
     * skipped-this-run set, matching the ordering the DAO layer uses.
     */
    private static String key(long nameIdA, long nameIdB) {
        return Math.min(nameIdA, nameIdB) + ":" + Math.max(nameIdA, nameIdB);
    }

    /**
     * A name together with its average embedding and representative face.
     */
    private static final class NameWithAverage {
        final NameRecord record;
        final float[] average;
        final FaceRecord representative;

        NameWithAverage(NameRecord record, float[] average, FaceRecord representative) {
            this.record = record;
            this.average = average;
            this.representative = representative;
        }
    }
}