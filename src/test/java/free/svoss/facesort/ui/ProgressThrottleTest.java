package free.svoss.facesort.ui;

import free.svoss.facesort.service.ImportService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProgressThrottle}: it forwards import progress to a
 * delegate at most once per interval while never losing the newest message.
 */
class ProgressThrottleTest {

    private static final long INTERVAL = 100;

    private static final class RecordingListener implements ImportService.ProgressListener {

        final List<String> messages = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onProgress(String message) {
            messages.add(message);
        }
    }

    @Test
    void theFirstMessageIsForwardedAtOnce() {
        AtomicLong clock = new AtomicLong();
        RecordingListener delegate = new RecordingListener();
        ProgressThrottle throttle = new ProgressThrottle(delegate, INTERVAL, clock::get);

        throttle.onProgress("a");
        throttle.flush();

        assertEquals(List.of("a"), delegate.messages);
    }

    @Test
    void messagesInsideTheIntervalAreHeldBackAndOnlyTheNewestIsForwarded() {
        AtomicLong clock = new AtomicLong();
        RecordingListener delegate = new RecordingListener();
        ProgressThrottle throttle = new ProgressThrottle(delegate, INTERVAL, clock::get);

        throttle.onProgress("a");
        throttle.onProgress("b");
        throttle.onProgress("c");
        assertEquals(List.of("a"), delegate.messages);

        throttle.flush();
        assertEquals(List.of("a", "c"), delegate.messages);
    }

    @Test
    void aMessageAfterTheIntervalIsForwardedAtOnce() {
        AtomicLong clock = new AtomicLong();
        RecordingListener delegate = new RecordingListener();
        ProgressThrottle throttle = new ProgressThrottle(delegate, INTERVAL, clock::get);

        throttle.onProgress("a");
        clock.set(INTERVAL);
        throttle.onProgress("b");
        assertEquals(List.of("a", "b"), delegate.messages);

        throttle.flush();
        assertEquals(List.of("a", "b"), delegate.messages);
    }

    @Test
    void aForwardedMessageDiscardsTheOneHeldBack() {
        AtomicLong clock = new AtomicLong();
        RecordingListener delegate = new RecordingListener();
        ProgressThrottle throttle = new ProgressThrottle(delegate, INTERVAL, clock::get);

        throttle.onProgress("a");          // forwarded
        throttle.onProgress("b");          // held back
        clock.set(INTERVAL);               // interval elapsed
        throttle.onProgress("c");          // forwarded, must discard "b"
        throttle.flush();

        assertEquals(List.of("a", "c"), delegate.messages);
    }

    @Test
    void flushWithoutAHeldMessageForwardsNothing() {
        AtomicLong clock = new AtomicLong();
        RecordingListener delegate = new RecordingListener();
        ProgressThrottle throttle = new ProgressThrottle(delegate, INTERVAL, clock::get);

        throttle.flush();

        assertEquals(List.of(), delegate.messages);
    }

    @Test
    void flushForwardsTheHeldMessageOnlyOnce() {
        AtomicLong clock = new AtomicLong();
        RecordingListener delegate = new RecordingListener();
        ProgressThrottle throttle = new ProgressThrottle(delegate, INTERVAL, clock::get);

        throttle.onProgress("a");
        throttle.onProgress("b");
        throttle.flush();
        throttle.flush();

        assertEquals(List.of("a", "b"), delegate.messages);
    }

    @Test
    void theDelegateIsNotUsedAsTheLock() {
        AtomicLong clock = new AtomicLong();
        AtomicBoolean heldDelegateLock = new AtomicBoolean();
        ImportService.ProgressListener delegate = new ImportService.ProgressListener() {
            @Override
            public void onProgress(String message) {
                if (Thread.holdsLock(this)) {
                    heldDelegateLock.set(true);
                }
            }
        };
        ProgressThrottle throttle = new ProgressThrottle(delegate, INTERVAL, clock::get);

        throttle.onProgress("a");
        throttle.onProgress("b");
        throttle.flush();

        assertFalse(heldDelegateLock.get(), "the throttle must not lock the delegate");
    }

    @Test
    void callbacksAreSerialized() throws Exception {
        int threads = 8;
        int perThread = 50;
        AtomicLong clock = new AtomicLong();
        AtomicInteger inside = new AtomicInteger();
        AtomicBoolean concurrentEntry = new AtomicBoolean();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ImportService.ProgressListener delegate = new ImportService.ProgressListener() {
            @Override
            public void onProgress(String message) {
                if (inside.incrementAndGet() > 1) {
                    concurrentEntry.set(true);
                }
                if (firstEntered.getCount() > 0) {
                    // Hold the first callback open until the test releases it,
                    // so a missing lock would let another worker enter here.
                    firstEntered.countDown();
                    awaitQuietly(releaseFirst);
                }
                inside.decrementAndGet();
            }
        };
        ProgressThrottle throttle = new ProgressThrottle(delegate, 1, clock::get);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        clock.incrementAndGet();
                        throttle.onProgress("m" + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();
        try {
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS), "the delegate must be called");
            Thread.sleep(10);           // let any unsynchronized caller pile up inside
        } finally {
            releaseFirst.countDown();
        }
        assertTrue(done.await(5, TimeUnit.SECONDS), "every worker must finish");

        assertFalse(concurrentEntry.get(), "the throttle must serialize callbacks");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
