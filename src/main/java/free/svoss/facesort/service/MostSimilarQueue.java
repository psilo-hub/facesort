package free.svoss.facesort.service;

import free.svoss.facesort.service.MostSimilarService.Suggestion;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Keeps the next few "Most similar" pairs ready so a decision can show the
 * next pair without scanning the whole library again.
 *
 * <p>The queue is filled in the background while the user looks at the current
 * pair. When a decision invalidates some queued entries (the face is tagged,
 * rejected or skipped, or dismissed as not a face) the caller drops exactly
 * those entries. {@link #next()} still re-checks every queued pair against the
 * database, so a change made elsewhere cannot make a stale pair appear.</p>
 *
 * <p>Methods are safe to call from the background prefetch task and from the
 * UI thread: the queue's own state is guarded by the instance monitor, and the
 * service calls happen outside it.</p>
 */
public final class MostSimilarQueue {

    /** Number of pairs kept ready ahead of the one on screen. */
    static final int DEFAULT_TARGET_SIZE = 3;

    private final MostSimilarService service;
    private final int targetSize;
    private final Deque<MostSimilarService.Suggestion> queued = new ArrayDeque<>();
    private MostSimilarService.Suggestion current;

    /**
     * Creates a queue that keeps {@value #DEFAULT_TARGET_SIZE} pairs ready.
     *
     * @param service the service that scans and validates pairs; must not be null
     */
    public MostSimilarQueue(MostSimilarService service) {
        this(service, DEFAULT_TARGET_SIZE);
    }

    /**
     * Creates a queue with an explicit target size, for tests and callers that
     * want a shorter or longer look-ahead.
     *
     * @param service    the service that scans and validates pairs; must not be null
     * @param targetSize number of pairs to keep ready; must be positive
     */
    MostSimilarQueue(MostSimilarService service, int targetSize) {
        this.service = Objects.requireNonNull(service, "service");
        if (targetSize < 1) {
            throw new IllegalArgumentException("targetSize must be positive");
        }
        this.targetSize = targetSize;
    }

    /**
     * Returns the next pair to show: a valid queued pair when one is ready,
     * otherwise the result of a fresh scan that also refills the queue.
     *
     * @param progress callback receiving (names scanned, total names) during
     *                 a fresh scan, or {@code null} for no progress reporting
     * @return the next pair, or empty when nothing is left to decide
     * @throws SQLException on database error
     */
    public Optional<MostSimilarService.Suggestion> next(BiConsumer<Integer, Integer> progress)
            throws SQLException {
        while (true) {
            MostSimilarService.Suggestion candidate;
            synchronized (this) {
                candidate = queued.pollFirst();
            }
            if (candidate == null) {
                return scan(progress);
            }
            if (service.isSuggestionAvailable(candidate)) {
                synchronized (this) {
                    current = candidate;
                }
                return Optional.of(candidate);
            }
        }
    }

    /**
     * Returns the next pair to show: a valid queued pair when one is ready,
     * otherwise the result of a fresh scan that also refills the queue.
     *
     * @return the next pair, or empty when nothing is left to decide
     * @throws SQLException on database error
     */
    public Optional<MostSimilarService.Suggestion> next() throws SQLException {
        return next(null);
    }

    /**
     * Refills the queue from a fresh scan, excluding the pair currently on
     * screen. Meant to run on a background task while the user decides.
     *
     * @param progress callback receiving (names scanned, total names), or
     *                 {@code null} for no progress reporting
     * @throws SQLException on database error
     */
    public void prefetch(BiConsumer<Integer, Integer> progress) throws SQLException {
        MostSimilarService.Suggestion anchor;
        synchronized (this) {
            anchor = current;
        }
        if (anchor == null) {
            return;
        }

        List<MostSimilarService.Suggestion> batch = service.findBestSuggestions(targetSize + 1, progress);
        List<MostSimilarService.Suggestion> fresh = new ArrayList<>();
        for (MostSimilarService.Suggestion suggestion : batch) {
            if (samePair(suggestion, anchor)) {
                continue;
            }
            if (!service.isSuggestionAvailable(suggestion)) {
                continue;
            }
            fresh.add(suggestion);
            if (fresh.size() >= targetSize) {
                break;
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        synchronized (this) {
            if (current != anchor) {
                return;
            }
            queued.clear();
            queued.addAll(fresh);
        }
    }

    /**
     * Refills the queue from a fresh scan, excluding the pair currently on
     * screen. Meant to run on a background task while the user decides.
     *
     * @throws SQLException on database error
     */
    public void prefetch() throws SQLException {
        prefetch(null);
    }

    /**
     * Tells whether a pair is already waiting, without touching the database.
     *
     * @return {@code true} when {@link #next()} will not need a fresh scan
     */
    public boolean hasQueued() {
        synchronized (this) {
            return !queued.isEmpty();
        }
    }

    /**
     * Drops the queued pair for exactly this face/name combination.
     *
     * @param faceId id of the unnamed face
     * @param nameId id of the name the pair was offered for
     */
    public void forgetPair(long faceId, long nameId) {
        synchronized (this) {
            queued.removeIf(suggestion -> samePair(suggestion, faceId, nameId));
        }
    }

    /**
     * Drops every queued pair that uses the given unnamed face, e.g. after it
     * was tagged or dismissed as not a face.
     *
     * @param faceId id of the unnamed face
     */
    public void forgetCandidate(long faceId) {
        synchronized (this) {
            queued.removeIf(suggestion -> suggestion.candidate().id() == faceId);
        }
    }

    /**
     * Forgets the current pair and everything queued, forcing the next call to
     * scan afresh. Used when a decision changes a name's average embedding and
     * can therefore reorder the whole queue.
     */
    public void clear() {
        synchronized (this) {
            queued.clear();
            current = null;
        }
    }

    /** Visible for tests: the pairs waiting behind the current one. */
    List<MostSimilarService.Suggestion> queued() {
        synchronized (this) {
            return List.copyOf(queued);
        }
    }

    private Optional<MostSimilarService.Suggestion> scan(BiConsumer<Integer, Integer> progress)
            throws SQLException {
        List<MostSimilarService.Suggestion> batch = service.findBestSuggestions(targetSize, progress);
        if (Thread.currentThread().isInterrupted()) {
            return Optional.empty();
        }
        synchronized (this) {
            current = batch.isEmpty() ? null : batch.get(0);
            queued.clear();
            if (!batch.isEmpty()) {
                queued.addAll(batch.subList(1, batch.size()));
            }
            return Optional.ofNullable(current);
        }
    }

    private static boolean samePair(MostSimilarService.Suggestion a,
                                    MostSimilarService.Suggestion b) {
        return samePair(a, b.candidate().id(), b.name().id());
    }

    private static boolean samePair(MostSimilarService.Suggestion suggestion,
                                    long faceId, long nameId) {
        return suggestion.candidate().id() == faceId && suggestion.name().id() == nameId;
    }
}
