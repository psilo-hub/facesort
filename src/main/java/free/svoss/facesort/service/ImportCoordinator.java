package free.svoss.facesort.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Runs the Import tab's combined import: the image import first, then the
 * video import, sequencing both through the same progress listener and the
 * same cancellation supplier so they stream into one log area and stop
 * together.
 */
public final class ImportCoordinator {

    /**
     * Aggregated result of a combined image + video import run.
     *
     * @param images the image-phase result
     * @param videos the video-phase result
     */
    /**
     * Aggregated result of a combined image + video import run.
     *
     * @param images the image-phase result
     * @param videos the video-phase result
     */
    public record CombinedImportResult(
            ImportService.ImportResult images,
            VideoImportService.VideoImportResult videos) {

        /** Total files (photos and videos) encountered by both phases. */
        public int total() {
            return images.totalFiles() + videos.totalVideos();
        }

        /** Total files processed by both phases. */
        public int processed() {
            return images.processed() + videos.processed();
        }

        /** Whether either phase was stopped via the cancellation supplier. */
        public boolean wasCancelled() {
            return images.wasCancelled() || videos.wasCancelled();
        }
    }

    private ImportCoordinator() {
        // Utility class.
    }

    /**
     * Runs the image import followed by the video import against the same
     * folder. Both phases report through {@code progress} and stop as soon as
     * {@code cancelled} reports {@code true}, whether that happens during the
     * image phase or between the phases.
     *
     * @param imageImports     the image import service; must not be null
     * @param videoImports     the video import service; must not be null
     * @param folder           the root directory to scan
     * @param progress         progress listener shared by both phases (may be null)
     * @param cancelled        cancellation supplier shared by both phases (may be null)
     * @param beforeVideoPhase invoked once between the two phases (may be null)
     * @return the combined result of both phases
     * @throws IOException if the folder cannot be traversed
     */
    public static CombinedImportResult run(ImportService imageImports, VideoImportService videoImports,
                                           Path folder, ImportService.ProgressListener progress,
                                           BooleanSupplier cancelled, Runnable beforeVideoPhase)
            throws IOException {
        ImportService.ImportResult images =
                imageImports.importFolder(folder, progress, cancelled);
        if (beforeVideoPhase != null) {
            beforeVideoPhase.run();
        }
        VideoImportService.VideoImportResult videos =
                videoImports.importFolder(folder, progress, cancelled);
        return new CombinedImportResult(images, videos);
    }
}