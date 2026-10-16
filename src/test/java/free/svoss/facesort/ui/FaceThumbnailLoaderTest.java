package free.svoss.facesort.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FaceThumbnailLoader}, the helper that decodes face crops off
 * the JavaFX application thread and delivers them back on it.
 *
 * <p>A {@code String} value type stands in for the decoded thumbnail, because
 * building a {@code javafx.scene.image.Image} needs a JavaFX toolkit. The decode
 * executor and the FX marshaller are injected, so a queued executor and a queued
 * marshaller stand in for the pool and {@code Platform.runLater}. Each request
 * names a destination (here a plain {@code new Object()}), so a test can pin
 * which generation of a crop a reused slot ends up showing.</p>
 */
class FaceThumbnailLoaderTest {

    private final AtomicInteger decodes = new AtomicInteger();

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private FaceThumbnailLoader<String> loader(int capacity, Executor executor,
                                               Consumer<Runnable> fxRunner) {
        return new FaceThumbnailLoader<>(capacity, jpg -> {
            decodes.incrementAndGet();
            return new String(jpg, StandardCharsets.UTF_8);
        }, executor, fxRunner);
    }

    @Test
    void anAbsentCropIsDeliveredAsNullWithoutDecoding() {
        FaceThumbnailLoader<String> loader = loader(10, Runnable::run, Runnable::run);
        Recorded recorded = new Recorded();

        loader.load(new Object(), 1L, null, recorded.consumer());
        loader.load(new Object(), 1L, new byte[0], recorded.consumer());

        assertEquals(List.of("<null>", "<null>"), recorded.values);
        assertEquals(0, decodes.get(), "there is nothing to decode");
    }

    @Test
    void aCropIsDecodedOnceAndReused() {
        FaceThumbnailLoader<String> loader = loader(10, Runnable::run, Runnable::run);
        Recorded recorded = new Recorded();
        Object destination = new Object();

        loader.load(destination, 1L, bytes("face-1"), recorded.consumer());
        loader.load(destination, 1L, bytes("face-1"), recorded.consumer());

        assertEquals(List.of("face-1", "face-1"), recorded.values);
        assertEquals(1, decodes.get(), "the second read must hit the cache");
    }

    @Test
    void differentFacesAreDecodedSeparately() {
        FaceThumbnailLoader<String> loader = loader(10, Runnable::run, Runnable::run);
        Recorded recorded = new Recorded();

        loader.load(new Object(), 1L, bytes("face-1"), recorded.consumer());
        loader.load(new Object(), 2L, bytes("face-2"), recorded.consumer());

        assertEquals(List.of("face-1", "face-2"), recorded.values);
        assertEquals(2, decodes.get());
    }

    @Test
    void aChangedCropForTheSameFaceIdIsDecodedAgain() {
        FaceThumbnailLoader<String> loader = loader(10, Runnable::run, Runnable::run);
        Recorded recorded = new Recorded();

        loader.load(new Object(), 1L, bytes("rotated"), recorded.consumer());
        loader.load(new Object(), 1L, bytes("upright"), recorded.consumer());

        assertEquals(List.of("rotated", "upright"), recorded.values);
        assertEquals(2, decodes.get(), "a rewritten crop must not reuse the old decode");
    }

    @Test
    void theDecodeIsNotRunOnTheCallingThread() {
        QueuedExecutor executor = new QueuedExecutor();
        FaceThumbnailLoader<String> loader = loader(10, executor, new QueuedFx());
        Recorded recorded = new Recorded();

        loader.load(new Object(), 1L, bytes("face-1"), recorded.consumer());

        assertEquals(0, decodes.get(), "the decode must be queued, not run inline");
        assertTrue(recorded.values.isEmpty(),
                "the callback must not fire before the decode completes");
    }

    @Test
    void theResultIsDeliveredOnTheFxThread() {
        QueuedExecutor executor = new QueuedExecutor();
        QueuedFx fx = new QueuedFx();
        FaceThumbnailLoader<String> loader = loader(10, executor, fx);
        Recorded recorded = new Recorded();

        loader.load(new Object(), 1L, bytes("face-1"), recorded.consumer());
        executor.runAll();

        assertTrue(recorded.values.isEmpty(),
                "the result must be marshalled to the FX thread, not delivered on the decoder thread");
        fx.runAll();

        assertEquals(List.of("face-1"), recorded.values);
        assertEquals(1, decodes.get());
    }

    @Test
    void concurrentRequestsForTheSameCropShareOneDecode() {
        QueuedExecutor executor = new QueuedExecutor();
        FaceThumbnailLoader<String> loader = loader(10, executor, Runnable::run);
        Recorded recorded = new Recorded();

        loader.load(new Object(), 1L, bytes("face-1"), recorded.consumer());
        loader.load(new Object(), 1L, bytes("face-1"), recorded.consumer());

        assertEquals(1, executor.size(),
                "a crop already being decoded must not be queued a second time");
        executor.runAll();

        assertEquals(List.of("face-1", "face-1"), recorded.values,
                "every waiter must be notified once");
        assertEquals(1, decodes.get(), "the shared decode must run once");
    }

    @Test
    void aDecodedCropIsServedFromTheCache() {
        QueuedExecutor executor = new QueuedExecutor();
        FaceThumbnailLoader<String> loader = loader(10, executor, Runnable::run);
        Recorded recorded = new Recorded();
        Object destination = new Object();

        loader.load(destination, 1L, bytes("face-1"), recorded.consumer());
        executor.runAll();

        loader.load(destination, 1L, bytes("face-1"), recorded.consumer());

        assertEquals(0, executor.size(), "a completed decode must not be queued again");
        assertEquals(List.of("face-1", "face-1"), recorded.values);
        assertEquals(1, decodes.get());
    }

    @Test
    void anUndecodableCropIsDeliveredAsNullAndNotCached() {
        AtomicInteger attempts = new AtomicInteger();
        FaceThumbnailLoader<String> loader = new FaceThumbnailLoader<>(10, jpg -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("bad jpeg");
        }, Runnable::run, Runnable::run);
        Recorded recorded = new Recorded();

        loader.load(new Object(), 1L, bytes("broken"), recorded.consumer());
        loader.load(new Object(), 1L, bytes("broken"), recorded.consumer());

        assertEquals(List.of("<null>", "<null>"), recorded.values);
        assertEquals(2, attempts.get(),
                "a failed decode must not be cached, so a later request retries");
    }

    @Test
    void anEvictedDecodeIsComputedAgain() {
        FaceThumbnailLoader<String> loader = loader(1, Runnable::run, Runnable::run);
        Recorded recorded = new Recorded();

        loader.load(new Object(), 1L, bytes("A"), recorded.consumer());
        loader.load(new Object(), 2L, bytes("B"), recorded.consumer());
        loader.load(new Object(), 1L, bytes("A"), recorded.consumer());

        assertEquals(List.of("A", "B", "A"), recorded.values);
        assertEquals(3, decodes.get(), "the evicted crop must be decoded again");
    }

    @Test
    void aSupersededRequestIsNotDelivered() {
        QueuedExecutor executor = new QueuedExecutor();
        QueuedFx fx = new QueuedFx();
        FaceThumbnailLoader<String> loader = loader(10, executor, fx);
        Object destination = new Object();
        Recorded older = new Recorded();
        Recorded newer = new Recorded();

        loader.load(destination, 1L, bytes("A"), older.consumer());
        loader.load(destination, 1L, bytes("B"), newer.consumer());

        executor.runAll();
        fx.runAll();

        assertEquals(List.of("B"), newer.values);
        assertTrue(older.values.isEmpty(),
                "only the latest crop requested for a destination must be delivered");
    }

    @Test
    void anOlderDecodeThatFinishesLastIsDropped() {
        QueuedExecutor executor = new QueuedExecutor();
        QueuedFx fx = new QueuedFx();
        FaceThumbnailLoader<String> loader = loader(10, executor, fx);
        Object destination = new Object();
        Recorded older = new Recorded();
        Recorded newer = new Recorded();

        loader.load(destination, 1L, bytes("A"), older.consumer());
        loader.load(destination, 1L, bytes("B"), newer.consumer());

        executor.runLast();
        fx.runAll();

        executor.runAll();
        fx.runAll();

        assertEquals(List.of("B"), newer.values);
        assertTrue(older.values.isEmpty(),
                "a decode that finishes after being superseded must be dropped");
    }

    @Test
    void aCachedValueSupersedesAnInFlightDecode() {
        QueuedExecutor executor = new QueuedExecutor();
        QueuedFx fx = new QueuedFx();
        FaceThumbnailLoader<String> loader = loader(10, executor, fx);
        Object destination = new Object();
        Recorded cached = new Recorded();
        Recorded stale = new Recorded();

        loader.load(destination, 1L, bytes("B"), value -> { });
        executor.runAll();
        fx.runAll();

        loader.load(destination, 1L, bytes("A"), stale.consumer());
        loader.load(destination, 1L, bytes("B"), cached.consumer());

        assertEquals(List.of("B"), cached.values,
                "a crop already cached must be delivered at once");

        executor.runAll();
        fx.runAll();

        assertTrue(stale.values.isEmpty(),
                "the in-flight decode must be dropped once a cached crop supersedes it");
    }

    @Test
    void aClearedCropSuppressesAnInFlightDecode() {
        QueuedExecutor executor = new QueuedExecutor();
        QueuedFx fx = new QueuedFx();
        FaceThumbnailLoader<String> loader = loader(10, executor, fx);
        Object destination = new Object();
        Recorded cleared = new Recorded();
        Recorded stale = new Recorded();

        loader.load(destination, 1L, bytes("A"), stale.consumer());
        loader.load(destination, 1L, null, cleared.consumer());

        assertEquals(List.of("<null>"), cleared.values,
                "clearing the crop must be delivered at once");

        executor.runAll();
        fx.runAll();

        assertTrue(stale.values.isEmpty(),
                "the in-flight decode must be dropped once the crop is cleared");
    }

    @Test
    void aForgottenDestinationIsNotDelivered() {
        QueuedExecutor executor = new QueuedExecutor();
        QueuedFx fx = new QueuedFx();
        FaceThumbnailLoader<String> loader = loader(10, executor, fx);
        Object destination = new Object();
        Recorded recorded = new Recorded();

        loader.load(destination, 1L, bytes("a"), recorded.consumer());
        loader.forget(destination);

        executor.runAll();
        fx.runAll();

        assertTrue(recorded.values.isEmpty(),
                "a forgotten destination must not receive a decode on its way to it");
    }

    @Test
    void aRejectedDecodeIsDeliveredAsNullAndIsNotWedged() {
        RejectingExecutor executor = new RejectingExecutor();
        FaceThumbnailLoader<String> loader = loader(10, executor, Runnable::run);
        Object destination = new Object();
        Recorded rejected = new Recorded();
        Recorded retried = new Recorded();

        loader.load(destination, 1L, bytes("face-1"), rejected.consumer());

        assertEquals(List.of("<null>"), rejected.values,
                "a decode that could not be queued must be reported as unavailable");

        loader.load(destination, 1L, bytes("face-1"), retried.consumer());
        executor.runAll();

        assertEquals(List.of("face-1"), retried.values,
                "a later request must retry instead of hanging on a wedged key");
        assertEquals(1, decodes.get(), "the rejected submission must not have decoded");
    }

    @Test
    void aFailingCallbackDoesNotStarveTheOtherWaiters() {
        QueuedExecutor executor = new QueuedExecutor();
        FaceThumbnailLoader<String> loader = loader(10, executor, Runnable::run);
        AtomicInteger broken = new AtomicInteger();
        Recorded recorded = new Recorded();

        loader.load(new Object(), 1L, bytes("face-1"), value -> {
            broken.incrementAndGet();
            throw new IllegalStateException("broken callback");
        });
        loader.load(new Object(), 1L, bytes("face-1"), recorded.consumer());

        executor.runAll();

        assertEquals(1, broken.get(), "the throwing callback must have been invoked");
        assertEquals(List.of("face-1"), recorded.values,
                "a throwing callback must not starve the other waiters");
    }

    /** Records delivered values, using a marker so a {@code null} delivery is visible. */
    private static final class Recorded {

        private final List<String> values = new ArrayList<>();

        Consumer<String> consumer() {
            return value -> values.add(value == null ? "<null>" : value);
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

        void runLast() {
            Runnable last = tasks.remove(tasks.size() - 1);
            last.run();
        }

        int size() {
            return tasks.size();
        }
    }

    /** An executor that rejects the first submission, then queues like {@link QueuedExecutor}. */
    private static final class RejectingExecutor implements Executor {

        private final QueuedExecutor delegate = new QueuedExecutor();
        private boolean rejected;

        @Override
        public void execute(Runnable command) {
            if (!rejected) {
                rejected = true;
                throw new IllegalStateException("executor unavailable");
            }
            delegate.execute(command);
        }

        void runAll() {
            delegate.runAll();
        }
    }

    /** A marshaller that records tasks instead of running them on the FX thread. */
    private static final class QueuedFx implements Consumer<Runnable> {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void accept(Runnable runnable) {
            tasks.add(runnable);
        }

        void runAll() {
            List<Runnable> queued = new ArrayList<>(tasks);
            tasks.clear();
            queued.forEach(Runnable::run);
        }
    }
}
