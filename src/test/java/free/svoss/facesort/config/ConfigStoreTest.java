package free.svoss.facesort.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ConfigStore}: the holder that publishes a changed
 * configuration atomically, so worker threads always observe a complete
 * config value instead of a half-updated one.
 */
class ConfigStoreTest {

    @Test
    void get_returnsTheInitialConfig() {
        ConfigModel initial = AppConfig.getDefault().withKnnK(7);

        ConfigStore store = new ConfigStore(initial);

        assertEquals(initial, store.get());
    }

    @Test
    void constructor_rejectsNull() {
        assertThrows(NullPointerException.class, () -> new ConfigStore(null));
    }

    @Test
    void set_publishesTheNewConfig() {
        ConfigStore store = new ConfigStore(AppConfig.getDefault());
        ConfigModel changed = AppConfig.getDefault().withDbName("other.db");

        store.set(changed);

        assertEquals(changed, store.get());
        assertNotSame(AppConfig.getDefault(), store.get(),
                "the store must hand out the value that was published, not a copy");
    }

    @Test
    void set_rejectsNull() {
        ConfigStore store = new ConfigStore(AppConfig.getDefault());

        assertThrows(NullPointerException.class, () -> store.set(null));
    }

    @Test
    void updateAndGet_appliesTheChangeToTheCurrentValueAndReturnsIt() {
        ConfigStore store = new ConfigStore(AppConfig.getDefault());

        ConfigModel updated = store.updateAndGet(config -> config.withKnnK(42));

        assertEquals(42, updated.knnK());
        assertEquals(updated, store.get());
        assertEquals(ConfigModel.DEFAULT_KNN_K, AppConfig.getDefault().knnK(),
                "the change must not leak into other config instances");
    }

    @Test
    void concurrentUpdates_loseNoUpdate() throws Exception {
        int threads = 8;
        int incrementsPerThread = 500;
        ConfigStore store = new ConfigStore(AppConfig.getDefault());
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < incrementsPerThread; j++) {
                    store.updateAndGet(config ->
                            config.withFaceNameMaxImages(config.faceNameMaxImages() + 1));
                }
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(30));
        }

        assertEquals(threads * incrementsPerThread + ConfigModel.DEFAULT_FACE_NAME_MAX_IMAGES,
                store.get().faceNameMaxImages(),
                "read-modify-write through the store must not lose concurrent updates");
    }
}
