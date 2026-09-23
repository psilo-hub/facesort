package free.svoss.facesort.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared worker pool driving the parallel photo and video import loops.
 *
 * <p>Files are handed to a fixed pool of daemon worker threads, at most one
 * worker per configured import thread and per available {@link FaceAiService}
 * (face models are not thread-safe, so each worker grabs its own service). All
 * database access stays serialized through the single synchronized connection
 * held by the DAOs. Each file is processed independently; task exceptions and
 * reported dropped faces are counted as errors but do not abort the run. The
 * call blocks until every worker has finished, so the caller never reports
 * completion while a worker is still importing.</p>
 */
final class ImportWorkerPool {

    private static final Logger LOG = Logger.getLogger(ImportWorkerPool.class.getName());

    /**
     * Runs {@code task} over every file in {@code files}.
     *
     * @param maxImportThreads the configured maximum number of import threads
     * @param faceAiServices   one face service per parallel worker (must not be empty)
     * @param threadNamePrefix prefix for the daemon worker threads
     * @param files            the files to process
     * @param progress         listener for progress messages (may be {@code null})
     * @param cancelled        supplier consulted before each file; when it returns
     *                         {@code true} the run stops (may be {@code null})
     * @param task             per-file work; return the number of faces dropped so
     *                         they are surfaced as errors
     * @return the aggregated outcome
     */
    static Result run(int maxImportThreads, List<FaceAiService> faceAiServices,
                      String threadNamePrefix, List<Path> files,
                      ImportService.ProgressListener progress,
                      BooleanSupplier cancelled, FileTask task) {
        int total = files.size();
        int threads = Math.min(Math.max(1, maxImportThreads), faceAiServices.size());

        AtomicInteger nextFile = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger processed = new AtomicInteger();
        AtomicBoolean stopped = new AtomicBoolean();

        AtomicInteger workerIds = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, threadNamePrefix + workerIds.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int w = 0; w < threads; w++) {
                final FaceAiService service = faceAiServices.get(w % faceAiServices.size());
                futures.add(pool.submit(() -> runWorker(service, files, total, progress,
                        cancelled, nextFile, errors, processed, stopped, task)));
            }
        } finally {
            pool.shutdown();
            awaitWorkerCompletion(futures);
        }
        return new Result(errors.get(), processed.get(), stopped.get());
    }

    /**
     * Single worker loop: pulls file indices from the shared counter until the
     * run is cancelled or all files are processed.
     */
    private static void runWorker(FaceAiService service, List<Path> files, int total,
                                  ImportService.ProgressListener progress,
                                  BooleanSupplier cancelled, AtomicInteger nextFile,
                                  AtomicInteger errors, AtomicInteger processed,
                                  AtomicBoolean stopped, FileTask task) {
        for (int i = nextFile.getAndIncrement(); i < total; i = nextFile.getAndIncrement()) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                stopped.set(true);
                return;
            }
            Path file = files.get(i);
            int index = i + 1;
            reportProgress(progress, String.format(Locale.ROOT,
                    "Started %d/%d: %s", index, total, file.getFileName()));
            try {
                if (task.run(service, file) > 0) {
                    // Faces lost to a crop/embedding/encoding failure are
                    // surfaced as a file error so they are not invisible to
                    // the user, while the remaining faces are still stored.
                    errors.incrementAndGet();
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error processing file: " + file, e);
                errors.incrementAndGet();
            }
            processed.incrementAndGet();
            reportProgress(progress, String.format(Locale.ROOT,
                    "Completed %d/%d: %s", index, total, file.getFileName()));
        }
    }

    private static void reportProgress(ImportService.ProgressListener progress, String message) {
        if (progress != null) {
            synchronized (progress) {
                progress.onProgress(message);
            }
        }
    }

    /**
     * Blocks until every worker future completes. Unbounded: the run must not
     * report completion while workers are still importing. An interruption (JVM
     * shutdown) stops the join and returns the current aggregate; a worker that
     * dies unexpectedly is logged and the remaining workers are still joined.
     */
    private static void awaitWorkerCompletion(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                LOG.log(Level.SEVERE, "Import worker failed unexpectedly", e.getCause());
            }
        }
    }

    /**
     * Per-file work dispatched by the pool.
     */
    @FunctionalInterface
    interface FileTask {

        /**
         * Processes a single file.
         *
         * @param service the worker's face detection and embedding service
         * @param file    the file to process
         * @return the number of faces dropped while processing the file; anyone
         *         above zero is surfaced as a file error
         * @throws Exception if the file cannot be processed (counted as an error)
         */
        int run(FaceAiService service, Path file) throws Exception;
    }

    /**
     * Outcome of one pool run.
     *
     * @param errors    files that failed processing or lost faces during detection
     * @param processed files actually processed before the run stopped
     * @param stopped   true if the run was stopped via the cancellation supplier
     */
    record Result(int errors, int processed, boolean stopped) {
    }
}