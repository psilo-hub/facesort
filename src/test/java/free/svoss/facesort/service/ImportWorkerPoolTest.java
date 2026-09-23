package free.svoss.facesort.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the shared {@link ImportWorkerPool} that drives the parallel
 * photo/video import loops.
 */
class ImportWorkerPoolTest {

    private static final String EXT = ".png";

    private static List<Path> files(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> Path.of("file" + i + EXT))
                .toList();
    }

    private static FaceAiService service() {
        return new FaceAiService(new FakeFaceAiEngine());
    }

    @Test
    void run_callsTaskForEveryFileAndJoinsAllWorkers() {
        List<Path> files = files(6);
        AtomicInteger done = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();

        ImportWorkerPool.Result result = ImportWorkerPool.run(2,
                List.of(service(), service()),
                "test-worker-", files, null, null,
                (service, file) -> {
                    done.incrementAndGet();
                    active.incrementAndGet();
                    active.decrementAndGet();
                    return 0;
                });

        assertEquals(6, result.processed());
        assertEquals(0, result.errors());
        assertEquals(files.size(), done.get());
        assertEquals(0, active.get(), "run() must only return after every worker finished");
    }

    @Test
    void run_executesTasksInParallel() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger overlapSeen = new AtomicInteger();

        ImportWorkerPool.Result result = ImportWorkerPool.run(2,
                List.of(service(), service()), "overlap-", files(2), null, null,
                (s, file) -> {
                    int now = active.incrementAndGet();
                    if (now >= 2) {
                        overlapSeen.incrementAndGet();
                    }
                    barrier.await();
                    active.decrementAndGet();
                    return 0;
                });

        assertEquals(2, result.processed());
        assertEquals(0, result.errors());
        assertTrue(overlapSeen.get() > 0, "both workers must be inside the task at the same time");
    }

    @Test
    void run_countsTaskExceptionsAsErrors() {
        ImportWorkerPool.Result result = ImportWorkerPool.run(1, List.of(service()),
                "err-", files(3), null, null, (s, file) -> {
                    throw new IllegalStateException("boom");
                });

        assertEquals(3, result.processed());
        assertEquals(3, result.errors());
    }

    @Test
    void run_countsDroppedFacesAsErrors() {
        ImportWorkerPool.Result result = ImportWorkerPool.run(1, List.of(service()),
                "drop-", files(2), null, null, (s, file) -> 3);

        assertEquals(2, result.processed());
        assertEquals(2, result.errors());
    }

    @Test
    void run_stopsWhenCancelled() {
        List<Path> files = files(6);
        AtomicInteger seen = new AtomicInteger();

        ImportWorkerPool.Result result = ImportWorkerPool.run(1, List.of(service()),
                "cancel-", files, null, () -> seen.get() >= 2,
                (s, file) -> {
                    seen.incrementAndGet();
                    return 0;
                });

        assertTrue(result.stopped());
        assertEquals(2, result.processed());
    }
}