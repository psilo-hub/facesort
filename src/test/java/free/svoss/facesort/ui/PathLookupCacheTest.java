package free.svoss.facesort.ui;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PathLookupCache}, the helper that resolves a face's stored
 * paths through one shared executor and remembers each image hash.
 *
 * <p>The executor is injected, so a direct executor (running the lookup on the
 * caller) and a queued executor (running it only when drained) stand in for the
 * one shared background thread. Nothing here needs a JavaFX toolkit.</p>
 */
class PathLookupCacheTest {

    @Test
    void looksUpThePathsForAnImageHash() {
        PathLookupCache cache = new PathLookupCache(Runnable::run, 10);
        RecordingLookup lookup = new RecordingLookup().storing("h1", "C:/a.jpg", "D:/b.jpg");
        List<List<String>> results = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();

        cache.lookup("h1", lookup, results::add, failures::add);

        assertEquals(List.of(List.of("C:/a.jpg", "D:/b.jpg")), results,
                "the stored paths must reach the caller");
        assertEquals(List.of("h1"), lookup.asked(), "the lookup must see the image hash");
        assertTrue(failures.isEmpty(), "a successful lookup must not report a failure");
    }

    @Test
    void doesNotBlockTheCaller() {
        QueuedExecutor executor = new QueuedExecutor();
        PathLookupCache cache = new PathLookupCache(executor, 10);
        RecordingLookup lookup = new RecordingLookup().storing("h1", "C:/a.jpg");
        List<List<String>> results = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();

        cache.lookup("h1", lookup, results::add, failures::add);

        assertTrue(results.isEmpty(),
                "the lookup must be queued, not run on the caller");
        assertEquals(0, lookup.calls(), "nothing must be looked up before the queue runs");

        executor.runAll();

        assertEquals(List.of(List.of("C:/a.jpg")), results,
                "draining the queue must report the paths");
        assertEquals(1, lookup.calls());
        assertTrue(failures.isEmpty());
    }

    @Test
    void looksEachImageHashUpOnlyOnce() {
        PathLookupCache cache = new PathLookupCache(Runnable::run, 10);
        RecordingLookup lookup = new RecordingLookup().storing("h1", "C:/a.jpg");
        List<List<String>> results = new ArrayList<>();

        cache.lookup("h1", lookup, results::add, failure -> { });
        cache.lookup("h1", lookup, results::add, failure -> { });

        assertEquals(1, lookup.calls(), "the same image hash must be looked up only once");
        assertEquals(List.of(List.of("C:/a.jpg"), List.of("C:/a.jpg")), results,
                "both callers must receive the remembered paths");
    }

    @Test
    void looksDifferentImageHashesUpSeparately() {
        PathLookupCache cache = new PathLookupCache(Runnable::run, 10);
        RecordingLookup lookup = new RecordingLookup()
                .storing("h1", "A")
                .storing("h2", "B");
        List<List<String>> results = new ArrayList<>();

        cache.lookup("h1", lookup, results::add, failure -> { });
        cache.lookup("h2", lookup, results::add, failure -> { });

        assertEquals(2, lookup.calls());
        assertEquals(List.of(List.of("A"), List.of("B")), results);
    }

    @Test
    void remembersAnEmptyPathList() {
        PathLookupCache cache = new PathLookupCache(Runnable::run, 10);
        RecordingLookup lookup = new RecordingLookup().storing("h1");
        List<List<String>> results = new ArrayList<>();

        cache.lookup("h1", lookup, results::add, failure -> { });
        cache.lookup("h1", lookup, results::add, failure -> { });

        assertEquals(1, lookup.calls(),
                "an empty path list is still a result worth remembering");
        assertEquals(List.of(List.of(), List.of()), results);
    }

    @Test
    void reportsAFailureWithoutCachingIt() {
        PathLookupCache cache = new PathLookupCache(Runnable::run, 10);
        RecordingLookup lookup = new RecordingLookup().failing("h1");
        List<List<String>> results = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();

        cache.lookup("h1", lookup, results::add, failures::add);

        assertEquals(1, failures.size(), "the failure must reach the failure callback");
        assertTrue(results.isEmpty(), "a failed lookup must not report paths");

        cache.lookup("h1", lookup, results::add, failures::add);

        assertEquals(2, lookup.calls(),
                "a failed lookup must not be remembered, so a later hover retries");
        assertEquals(2, failures.size());
    }

    @Test
    void forgetsEverythingOnInvalidate() {
        PathLookupCache cache = new PathLookupCache(Runnable::run, 10);
        RecordingLookup lookup = new RecordingLookup().storing("h1", "C:/a.jpg");
        List<List<String>> results = new ArrayList<>();

        cache.lookup("h1", lookup, results::add, failure -> { });
        cache.clear();
        cache.lookup("h1", lookup, results::add, failure -> { });

        assertEquals(2, lookup.calls(),
                "after invalidation the hash must be looked up again");
        assertEquals(List.of(List.of("C:/a.jpg"), List.of("C:/a.jpg")), results);
    }

    @Test
    void keepsAtMostTheConfiguredNumberOfHashes() {
        PathLookupCache cache = new PathLookupCache(Runnable::run, 2);
        RecordingLookup lookup = new RecordingLookup()
                .storing("h1", "A")
                .storing("h2", "B")
                .storing("h3", "C");

        cache.lookup("h1", lookup, paths -> { }, failure -> { });
        cache.lookup("h2", lookup, paths -> { }, failure -> { });
        cache.lookup("h3", lookup, paths -> { }, failure -> { });
        int before = lookup.calls();

        cache.lookup("h1", lookup, paths -> { }, failure -> { });

        assertEquals(1, lookup.calls() - before,
                "the least recently used hash must have been evicted");
    }

    @Test
    void handlesConcurrentLookups() throws InterruptedException {
        int hashCount = 32;
        RecordingLookup lookup = new RecordingLookup();
        for (int i = 0; i < hashCount; i++) {
            lookup.storing("h" + i, "path-" + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4, daemonThreads());
        PathLookupCache cache = new PathLookupCache(pool, 64);
        Map<String, List<String>> results = new ConcurrentHashMap<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> callers = new ArrayList<>();
        int callerCount = 6;
        for (int c = 0; c < callerCount; c++) {
            Thread caller = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < hashCount; i++) {
                    String hash = "h" + i;
                    cache.lookup(hash, lookup, paths -> results.put(hash, paths), failures::add);
                }
            }, "path-caller-" + c);
            caller.setDaemon(true);
            callers.add(caller);
            caller.start();
        }

        start.countDown();
        for (Thread caller : callers) {
            caller.join();
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS),
                "the lookup pool must terminate");

        assertTrue(failures.isEmpty(), "concurrent lookups must not fail: " + failures);
        assertEquals(hashCount, results.size(), "every hash must be answered");
        for (int i = 0; i < hashCount; i++) {
            assertEquals(List.of("path-" + i), results.get("h" + i),
                    "hash h" + i + " must resolve to its own paths");
        }
        // Two callers that miss the same hash at the same time may both look it
        // up, which is a harmless repeated read, so only the lower bound holds.
        assertTrue(lookup.calls() >= hashCount,
                "every image hash must be looked up at least once");
    }

    @Test
    void aRunningLookupDoesNotBlockOtherRequests() throws Exception {
        CountDownLatch insideLookup = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        ExecutorService lookupThread = Executors.newSingleThreadExecutor(daemonThreads());
        ExecutorService probeThread = Executors.newSingleThreadExecutor(daemonThreads());
        try {
            PathLookupCache cache = new PathLookupCache(lookupThread, 8);
            FaceUi.Lookup<List<String>> slowLookup = hash -> {
                insideLookup.countDown();
                awaitQuietly(releaseLookup);
                return List.of("/photos/" + hash);
            };

            cache.lookup("aaa", slowLookup, paths -> { }, error -> { });
            assertTrue(insideLookup.await(5, TimeUnit.SECONDS), "the first lookup must start");

            // While that lookup is still running, another request and a cache
            // clear must not wait for it (they run on the FX thread in the app).
            Future<?> other = probeThread.submit(() -> {
                cache.lookup("bbb", slowLookup, paths -> { }, error -> { });
                cache.clear();
            });
            other.get(5, TimeUnit.SECONDS);
        } finally {
            releaseLookup.countDown();
            lookupThread.shutdownNow();
            probeThread.shutdownNow();
        }
    }

    @Test
    void aQueuedRequestForARunningHashDoesNotRepeatTheLookup() throws Exception {
        CountDownLatch insideLookup = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(2);
        ExecutorService lookupThread = Executors.newSingleThreadExecutor(daemonThreads());
        try {
            PathLookupCache cache = new PathLookupCache(lookupThread, 8);
            AtomicInteger calls = new AtomicInteger();
            List<List<String>> results = new CopyOnWriteArrayList<>();
            FaceUi.Lookup<List<String>> slowLookup = hash -> {
                calls.incrementAndGet();
                insideLookup.countDown();
                awaitQuietly(releaseLookup);
                return List.of("/photos/" + hash);
            };

            cache.lookup("h1", slowLookup, paths -> { results.add(paths); answered.countDown(); },
                    error -> { });
            assertTrue(insideLookup.await(5, TimeUnit.SECONDS), "the first lookup must start");

            // This one misses, because the first result is not remembered yet,
            // and is queued behind the first task; it must reuse that result.
            cache.lookup("h1", slowLookup, paths -> { results.add(paths); answered.countDown(); },
                    error -> { });
            releaseLookup.countDown();

            assertTrue(answered.await(5, TimeUnit.SECONDS), "both requests must be answered");
            assertEquals(1, calls.get(), "the queued request must reuse the running lookup's result");
            assertEquals(List.of(List.of("/photos/h1"), List.of("/photos/h1")), results);
        } finally {
            releaseLookup.countDown();
            lookupThread.shutdownNow();
        }
    }

    @Test
    void aLookupQueuedBeforeAnInvalidationIsNotRemembered() {
        QueuedExecutor executor = new QueuedExecutor();
        PathLookupCache cache = new PathLookupCache(executor, 10);
        RecordingLookup lookup = new RecordingLookup().storing("h1", "C:/a.jpg");
        List<List<String>> results = new ArrayList<>();

        cache.lookup("h1", lookup, results::add, failure -> { });
        cache.clear();
        executor.runAll();
        cache.lookup("h1", lookup, results::add, failure -> { });
        executor.runAll();

        assertEquals(2, lookup.calls(),
                "a lookup queued before the invalidation must not be remembered");
        assertEquals(List.of(List.of("C:/a.jpg"), List.of("C:/a.jpg")), results);
    }

    /** Waits for the latch, restoring the interrupt flag if interrupted. */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** A single daemon thread factory for the lookup pool. */
    private static java.util.concurrent.ThreadFactory daemonThreads() {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "path-lookup-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** A lookup that returns preset paths and records what it was asked for. */
    private static final class RecordingLookup implements FaceUi.Lookup<List<String>> {

        private final Map<String, List<String>> paths = new ConcurrentHashMap<>();
        private final List<String> asked = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger calls = new AtomicInteger();
        private volatile String failingHash;

        RecordingLookup storing(String hash, String... storedPaths) {
            paths.put(hash, List.of(storedPaths));
            return this;
        }

        RecordingLookup failing(String hash) {
            this.failingHash = hash;
            return this;
        }

        @Override
        public List<String> byHash(String hash) throws SQLException {
            calls.incrementAndGet();
            asked.add(hash);
            if (hash.equals(failingHash)) {
                throw new SQLException("database unavailable");
            }
            return paths.getOrDefault(hash, List.of());
        }

        int calls() {
            return calls.get();
        }

        List<String> asked() {
            synchronized (asked) {
                return new ArrayList<>(asked);
            }
        }
    }

    /** An executor that records submitted tasks instead of running them. */
    private static final class QueuedExecutor implements Executor {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            List<Runnable> queued = new ArrayList<>(tasks);
            tasks.clear();
            queued.forEach(Runnable::run);
        }
    }
}
