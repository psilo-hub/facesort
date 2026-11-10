package free.svoss.facesort.service;

import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.db.NotMatchDao;
import free.svoss.facesort.db.TransactionRunner;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.model.SimilarityResult;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;

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
    private final FaceDao faceDao;
    private final NameDao nameDao;
    private final NotMatchDao notMatchDao;
    private final TransactionRunner transactionRunner;
    private final UnnamedFacePool pool;
    private final UnnamedFaceHnswIndex hnswIndex;
    private final Set<MatchKey> skipped =
            Collections.synchronizedSet(new HashSet<>());
    /**
     * Cancellation flag for in-progress scans. Set by {@link #cancel()},
     * checked between names in the scoring loop, and reset at the start of
     * every scan so a cancelled scan does not poison later ones.
     */
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /**
     * Creates the service.
     *
     * @param faceToNameService ranking of candidates and tagging; must not be null
     * @param faceAiService     engine for embedding math; must not be null
     * @param faceDao           data access for faces; must not be null
     * @param nameDao           data access for names; must not be null
     * @param notMatchDao       data access for rejected face/name combinations; must not be null
     * @param transactionRunner runs batch decisions as one atomic transaction; must not be null
     */
    public MostSimilarService(FaceToNameService faceToNameService, FaceAiService faceAiService,
                              FaceDao faceDao, NameDao nameDao, NotMatchDao notMatchDao,
                              TransactionRunner transactionRunner) {
        this.faceToNameService = Objects.requireNonNull(faceToNameService, "faceToNameService");
        this.faceAiService = Objects.requireNonNull(faceAiService, "faceAiService");
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
        this.nameDao = Objects.requireNonNull(nameDao, "nameDao");
        this.notMatchDao = Objects.requireNonNull(notMatchDao, "notMatchDao");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.pool = new UnnamedFacePool(faceDao);
        this.hnswIndex = new UnnamedFaceHnswIndex(pool, faceToNameService.configStore());
    }

    /**
     * Cancels any in-progress scan. The scoring loop checks this flag between
     * names and returns empty promptly. The flag is reset at the start of the
     * next scan, so a cancellation does not affect later scans.
     */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * Tells whether the current scan has been cancelled.
     *
     * @return {@code true} when {@link #cancel()} has been called and the
     *         current scan has not been reset yet
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Threshold (number of unnamed faces) above which the HNSW index is used
     * for scoring instead of an exact full-pool scan. Below it, building the
     * index costs more than it saves. Tuned so HNSW only kicks in for large
     * libraries where O(names x log unnamed) beats O(names x unnamed).
     * Mutable for tests.
     */
    static int HNSW_THRESHOLD = 5000;

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
     * <p>Names are scored in parallel against the shared unnamed pool. As soon
     * as any name's best candidate reaches the configured early-exit
     * threshold ({@link FaceToNameService#minEarlyExitSimilarity()}), the
     * scan stops: the result is then "first pair at or above the threshold"
     * rather than the strict global best, because a later name might score
     * even higher. Ties resolve deterministically: the lower name id wins.</p>
     *
     * @return the best pair still on offer, or empty when there is none
     * @throws SQLException on database error
     */
    public Optional<Suggestion> findBestSuggestion() throws SQLException {
        return findBestSuggestion(null);
    }

    /**
     * Returns the most similar (name, unnamed face) pair of the whole library,
     * reporting scan progress through {@code progress} and honouring
     * {@link #cancel()}.
     *
     * <p>{@code progress.accept(scanned, total)} is called after each name is
     * processed, so a UI can show {@code "scanning 120 / 480 names"}. When the
     * scan is cancelled, the method returns empty promptly.</p>
     *
     * @param progress callback receiving (names scanned, total names), or
     *                 {@code null} for no progress reporting
     * @return the best pair still on offer, or empty when there is none or the
     *         scan was cancelled
     * @throws SQLException on database error
     */
    public Optional<Suggestion> findBestSuggestion(BiConsumer<Integer, Integer> progress)
            throws SQLException {
        cancelled.set(false);
        Scan scan = loadScan();
        if (scan.names().isEmpty()) {
            return Optional.empty();
        }

        double earlyExitThreshold = faceToNameService.minEarlyExitSimilarity();
        int total = scan.names().size();
        AtomicInteger scanned = new AtomicInteger();

        // Build the HNSW index when the unnamed pool is large enough that an
        // approximate index beats an exact full-pool scan per name. The index
        // is read-only during the parallel section, so it's safe to share.
        final UnnamedFaceHnswIndex hnsw;
        if (scan.unnamed().size() >= HNSW_THRESHOLD) {
            hnswIndex.ensureBuilt();
            hnsw = hnswIndex;
        } else {
            hnsw = null;
        }

        // Score every name against the shared unnamed pool in parallel. Each
        // thread skips its name when a pair above the early-exit threshold
        // has already been found, or when the scan has been cancelled. The
        // shared reference holds the best pair seen so far; ties keep the
        // lower name id. All database reads happened in loadScan(), so the
        // parallel section touches no JDBC state.
        AtomicReference<BestPair> best = new AtomicReference<>(null);
        scan.names().stream().parallel().forEach(name -> {
            if (isCancelled()) {
                return;
            }
            BestPair current = best.get();
            if (current != null
                    && current.candidate().similarity() > earlyExitThreshold) {
                return;
            }
            BestPair pair = hnsw != null
                    ? bestPairForHnsw(scan, name, hnsw)
                    : bestPairFor(scan, name);
            if (pair == null) {
                return;
            }
            best.updateAndGet(existing -> better(existing, pair));
            int s = scanned.incrementAndGet();
            if (progress != null) {
                progress.accept(s, total);
            }
        });

        if (isCancelled()) {
            return Optional.empty();
        }

        BestPair result = best.get();
        if (result == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                suggestionFor(result.name(), result.candidate(), new HashMap<>()));
    }

    /**
     * Returns the better of two pairs: higher similarity wins; exact ties keep
     * the lower name id for deterministic results.
     */
    private static BestPair better(BestPair a, BestPair b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (b.candidate().similarity() > a.candidate().similarity()) {
            return b;
        }
        if (b.candidate().similarity() == a.candidate().similarity()
                && b.name().id() < a.name().id()) {
            return b;
        }
        return a;
    }

    /**
     * Scores one name against the shared unnamed pool and returns its best
     * qualifying candidate, or null when the name has none left to offer.
     */
    private BestPair bestPairFor(Scan scan, NameRecord name) {
        float[] average = scan.averages().get(name.id());
        if (average == null) {
            return null;
        }
        SimilarityResult candidate = bestNotSkipped(name.id(), average, scan.unnamed(),
                scan.rejected().getOrDefault(name.id(), Set.of()), scan.cutoff());
        return candidate == null ? null : new BestPair(name, candidate);
    }

    /**
     * Scores one name against the shared unnamed pool using the HNSW index for
     * approximate nearest-neighbour search, then re-scores the top candidates
     * exactly and filters rejected/skipped/below-cutoff ones.
     *
     * @param scan    the shared scan data
     * @param name    the name to score candidates for
     * @param hnsw    the HNSW index over the unnamed pool; must not be null
     * @return the name's best qualifying candidate, or null when the name has
     *         none left to offer
     */
    private BestPair bestPairForHnsw(Scan scan, NameRecord name,
                                     UnnamedFaceHnswIndex hnsw) {
        float[] average = scan.averages().get(name.id());
        if (average == null) {
            return null;
        }
        int poolSize = scan.unnamed().size();
        // Over-fetch: query more candidates than needed so that after
        // filtering (rejected, skipped, below-cutoff) we still have a good
        // candidate. Cap at MAX_K to bound the exact re-scoring cost.
        int k = Math.min(poolSize, Math.max(50, poolSize / 4));
        List<Long> candidateIds = hnsw.query(average, k);
        if (candidateIds.isEmpty()) {
            return null;
        }
        Set<Long> rejected = scan.rejected().getOrDefault(name.id(), Set.of());
        double cutoff = scan.cutoff();
        SimilarityResult best = null;
        for (Long faceId : candidateIds) {
            FaceRecord face = scan.unnamedById().get(faceId);
            if (face == null) {
                continue; // no longer in the pool
            }
            if (rejected.contains(faceId)) {
                continue;
            }
            if (skipped.contains(new MatchKey(face.id(), name.id()))) {
                continue;
            }
            double similarity = faceAiService.calcSimilarity(average, face.embedding());
            if (similarity < cutoff) {
                continue;
            }
            if (best == null || similarity > best.similarity()) {
                best = new SimilarityResult(face, similarity);
            }
        }
        return best == null ? null : new BestPair(name, best);
    }

    /** One name's best candidate, for reduction to the global best pair. */
    private record BestPair(NameRecord name, SimilarityResult candidate) {
    }

    /**
     * Scores the name at the given position against the shared unnamed pool,
     * returning its best candidate tagged with the name's scan order for
     * stable tie-breaking.
     */
    private RankedName rankName(Scan scan, int order) {
        NameRecord name = scan.names().get(order);
        float[] average = scan.averages().get(name.id());
        if (average == null) {
            return null;
        }
        SimilarityResult candidate = bestNotSkipped(name.id(), average, scan.unnamed(),
                scan.rejected().getOrDefault(name.id(), Set.of()), scan.cutoff());
        return candidate == null ? null : new RankedName(order, name, candidate);
    }

    /**
     * Scores one name against the shared unnamed pool using the HNSW index for
     * approximate nearest-neighbour search, returning its best qualifying
     * candidate tagged with the name's scan order for stable tie-breaking.
     */
    private RankedName rankNameHnsw(Scan scan, int order,
                                    UnnamedFaceHnswIndex hnsw) {
        NameRecord name = scan.names().get(order);
        float[] average = scan.averages().get(name.id());
        if (average == null) {
            return null;
        }
        BestPair pair = bestPairForHnsw(scan, name, hnsw);
        return pair == null ? null : new RankedName(order, pair.name(), pair.candidate());
    }

    /**
     * Returns up to {@code limit} (name, unnamed face) pairs with the highest
     * similarity, ordered by descending similarity. A face appears at most once,
     * paired with the name that scores it highest, so the list can be shown or
     * prefetched without offering the same unnamed face twice.
     *
     * <p>All names are scored in parallel against the shared unnamed pool, so
     * the result is the same as a sequential scan: exact ties keep the name
     * that appeared first in the scan order.</p>
     *
     * @param limit maximum number of pairs; values &le; 0 yield an empty list
     * @return the best pairs in descending similarity order
     * @throws SQLException on database error
     */
    public List<Suggestion> findBestSuggestions(int limit) throws SQLException {
        return findBestSuggestions(limit, null);
    }

    /**
     * Returns up to {@code limit} (name, unnamed face) pairs with the highest
     * similarity, reporting scan progress through {@code progress} and
     * honouring {@link #cancel()}.
     *
     * <p>{@code progress.accept(scanned, total)} is called after each name is
     * processed, so a UI can show {@code "scanning 120 / 480 names"}. When the
     * scan is cancelled, the method returns an empty list promptly.</p>
     *
     * @param limit    maximum number of pairs; values &le; 0 yield an empty list
     * @param progress callback receiving (names scanned, total names), or
     *                 {@code null} for no progress reporting
     * @return the best pairs in descending similarity order, or empty when
     *         cancelled
     * @throws SQLException on database error
     */
    public List<Suggestion> findBestSuggestions(int limit,
                                                BiConsumer<Integer, Integer> progress)
            throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        cancelled.set(false);
        Scan scan = loadScan();
        if (scan.names().isEmpty()) {
            return List.of();
        }

        int total = scan.names().size();
        AtomicInteger scanned = new AtomicInteger();

        // Build the HNSW index when the unnamed pool is large enough.
        final UnnamedFaceHnswIndex hnsw;
        if (scan.unnamed().size() >= HNSW_THRESHOLD) {
            hnswIndex.ensureBuilt();
            hnsw = hnswIndex;
        } else {
            hnsw = null;
        }

        // Score every name against the shared unnamed pool in parallel. Each
        // thread skips its name when the scan has been cancelled. The order
        // field preserves the original sequence for stable tie-breaking after
        // the parallel reduction.
        List<RankedName> ranked = IntStream.range(0, scan.names().size()).parallel()
                .mapToObj(order -> {
                    if (isCancelled()) {
                        return null;
                    }
                    RankedName entry = hnsw != null
                            ? rankNameHnsw(scan, order, hnsw)
                            : rankName(scan, order);
                    int s = scanned.incrementAndGet();
                    if (progress != null) {
                        progress.accept(s, total);
                    }
                    return entry;
                })
                .filter(Objects::nonNull)
                .toList();
        if (isCancelled() || ranked.isEmpty()) {
            return List.of();
        }

        Map<Long, RankedName> bestByCandidate = new HashMap<>();
        for (RankedName entry : ranked) {
            long faceId = entry.candidate().faceRecord().id();
            RankedName existing = bestByCandidate.get(faceId);
            if (existing == null
                    || entry.candidate().similarity() > existing.candidate().similarity()
                    || (entry.candidate().similarity() == existing.candidate().similarity()
                            && entry.order() < existing.order())) {
                bestByCandidate.put(faceId, entry);
            }
        }
        List<RankedName> sorted = new ArrayList<>(bestByCandidate.values());
        sorted.sort(Comparator
                .comparingDouble((RankedName entry) -> entry.candidate().similarity())
                .reversed()
                .thenComparingInt(RankedName::order));

        List<Suggestion> suggestions = new ArrayList<>();
        Map<Long, FaceRecord> representatives = new HashMap<>();
        for (RankedName entry : sorted) {
            Suggestion suggestion = suggestionFor(entry.name(), entry.candidate(), representatives);
            if (suggestion != null) {
                suggestions.add(suggestion);
            }
            if (suggestions.size() == limit) {
                break;
            }
        }
        return suggestions;
    }

    /**
     * Tells whether a pair is still offerable: its name still exists, its face
     * is still unnamed and not dismissed, and the user has neither rejected nor
     * skipped exactly that combination. Used to drop prefetched pairs that a
     * decision or another tab invalidated while they waited in the queue.
     *
     * @param suggestion the pair to check; must not be null
     * @return {@code true} when the pair may still be shown
     * @throws SQLException on database error
     */
    public boolean isSuggestionAvailable(Suggestion suggestion) throws SQLException {
        Objects.requireNonNull(suggestion, "suggestion");
        long faceId = suggestion.candidate().id();
        long nameId = suggestion.name().id();
        if (skipped.contains(new MatchKey(faceId, nameId))) {
            return false;
        }
        if (notMatchDao.isNotMatch(faceId, nameId)) {
            return false;
        }
        if (nameDao.findById(nameId).isEmpty()) {
            return false;
        }
        return faceToNameService.isUnnamedCandidate(faceId);
    }

    private Scan loadScan() throws SQLException {
        List<NameRecord> names = nameDao.findAll();
        if (names.isEmpty()) {
            return new Scan(names, Map.of(), Map.of(), List.of(), Map.of(), 0.0);
        }
        List<Long> nameIds = names.stream().map(NameRecord::id).toList();
        Map<Long, float[]> averages = faceToNameService.averageEmbeddingsFor(nameIds);
        Map<Long, Set<Long>> rejected = notMatchDao.findFaceIdsByNameIds(nameIds);
        List<FaceRecord> unnamed = pool.get();
        Map<Long, FaceRecord> unnamedById = new HashMap<>();
        for (FaceRecord face : unnamed) {
            unnamedById.put(face.id(), face);
        }
        double cutoff = faceToNameService.minNameSimilarity();
        return new Scan(names, averages, rejected, unnamed, unnamedById, cutoff);
    }

    /**
     * Builds the suggestion for one name/candidate pair, loading the name's
     * representative tagged face at most once across the batch and hydrating
     * only the faces a returned suggestion needs.
     */
    private Suggestion suggestionFor(NameRecord name, SimilarityResult candidate,
                                     Map<Long, FaceRecord> representatives) throws SQLException {
        FaceRecord taggedFace = representatives.get(name.id());
        if (taggedFace == null) {
            List<SimilarityResult> tagged = faceToNameService.findMostSimilarNamedSlim(name.id(), 1);
            if (tagged.isEmpty()) {
                return null;
            }
            taggedFace = tagged.get(0).faceRecord();
            representatives.put(name.id(), taggedFace);
        }
        double faceSim = faceAiService.calcSimilarity(taggedFace.embedding(),
                candidate.faceRecord().embedding());
        return hydrate(new Suggestion(name, taggedFace, candidate.faceRecord(),
                candidate.similarity(), faceSim)).orElse(null);
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
        pool.remove(faceId);
        hnswIndex.markDead(faceId);
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
     * Tags every selected candidate with its suggested name in a single
     * transaction, so one batch decision replaces K full rescans with roughly
     * one. Each candidate leaves the unnamed pool and the name-average cache
     * invalidates itself through {@link FaceDao#writeVersion()}, so the next
     * scan picks up the new averages. Session-only skips for the same pairs
     * are dropped as well, since they are superseded by the tag.
     *
     * @param selections the pairs to tag; each candidate is tagged with its name
     * @throws SQLException on database error
     */
    public void tagSelected(List<Suggestion> selections) throws SQLException {
        Objects.requireNonNull(selections, "selections");
        transactionRunner.inTransaction(() -> {
            for (Suggestion selection : selections) {
                faceToNameService.tagFace(selection.candidate().id(), selection.name().id());
            }
            return null;
        });
        for (Suggestion selection : selections) {
            pool.remove(selection.candidate().id());
            hnswIndex.markDead(selection.candidate().id());
        }
        skipped.removeIf(key -> selections.stream()
                .anyMatch(s -> s.candidate().id() == key.faceId() && s.name().id() == key.nameId()));
    }

    /**
     * Records that every selected candidate is not its suggested person, in a
     * single transaction. The rejections persist so neither naming tab offers
     * those combinations again; each face stays a candidate for other names.
     *
     * @param selections the pairs to reject
     * @throws SQLException on database error
     */
    public void rejectSelected(List<Suggestion> selections) throws SQLException {
        Objects.requireNonNull(selections, "selections");
        transactionRunner.inTransaction(() -> {
            for (Suggestion selection : selections) {
                notMatchDao.insert(selection.candidate().id(), selection.name().id());
            }
            return null;
        });
    }

    /**
     * Hides every selected pair for the current session only; nothing is
     * written to the database. A later run may offer the pairs again.
     *
     * @param selections the pairs to skip
     */
    public void skipAll(List<Suggestion> selections) {
        Objects.requireNonNull(selections, "selections");
        for (Suggestion selection : selections) {
            skipped.add(new MatchKey(selection.candidate().id(), selection.name().id()));
        }
    }

    /**
     * Returns the current backlog: how many unnamed faces are still available
     * for tagging and how many names have tagged faces. Uses cheap
     * {@code COUNT(*)} queries, no full similarity scan.
     *
     * @return the backlog count
     * @throws SQLException on database error
     */
    public BacklogCount getBacklogCount() throws SQLException {
        long unnamed = faceDao.countUnnamed();
        long namesWithFaces = nameDao.countWithFaces();
        return new BacklogCount(unnamed, namesWithFaces);
    }

    /**
     * The backlog: how many unnamed faces remain and how many names have
     * tagged faces.
     *
     * @param unnamedFaces   unnamed, not-dismissed faces available for tagging
     * @param namesWithFaces names that have at least one tagged face
     */
    public record BacklogCount(long unnamedFaces, long namesWithFaces) {
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

    /** The shared data one scan scores every name against. */
    private record Scan(List<NameRecord> names, Map<Long, float[]> averages,
                        Map<Long, Set<Long>> rejected, List<FaceRecord> unnamed,
                        Map<Long, FaceRecord> unnamedById, double cutoff) {
    }

    /** A name's best candidate, with the name's order in the scan for stable ties. */
    private record RankedName(int order, NameRecord name, SimilarityResult candidate) {
    }

    /** An unnamed face paired with a name, as identified for session skips. */
    private record MatchKey(long faceId, long nameId) {
    }
}
