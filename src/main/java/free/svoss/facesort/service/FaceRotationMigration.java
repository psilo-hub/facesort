package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.config.ConfigStore;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.MetaDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.util.ImageUtils;
import free.svoss.tools.faceai.DetectedFace;

import java.awt.image.BufferedImage;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One-time pass that straightens the face crops stored by earlier builds.
 *
 * <p>Builds before FaceAI exposed facial landmarks could not tell that a face
 * was recorded sideways or upside down, so those faces were stored rotated.
 * This migration re-derives each affected crop from the original file: it
 * re-detects the face, finds the stored bounding box again, rotates the crop
 * upright, and replaces the stored embedding and thumbnail. Bounding boxes and
 * assigned names are never touched — they identify the face and are referenced
 * elsewhere.</p>
 *
 * <p>The pass is deliberately conservative. A stored face whose fresh detection
 * does not match, whose original file is gone, or which already stands upright
 * is left exactly as it is. Completion is recorded in the {@code meta} table, so
 * later startups do no work at all; a pass that is cancelled before finishing is
 * not recorded and simply runs again next time.</p>
 *
 * <p>Re-running is safe: an already-straightened face is detected as upright and
 * skipped, so an interrupted pass picks up where it left off.</p>
 */
public final class FaceRotationMigration implements AutoCloseable {

    /** Key under which a completed pass is recorded in the {@code meta} table. */
    static final String COMPLETION_KEY = "face_rotation_migration";

    /**
     * Minimum intersection-over-union between a stored bounding box and a fresh
     * detection for the two to be considered the same face. High enough that a
     * different face nearby is not mistaken for it, low enough to absorb the
     * small variation between detector runs.
     */
    static final double MIN_MATCH_IOU = 0.5;

    private static final Logger LOG = Logger.getLogger(FaceRotationMigration.class.getName());

    private final FaceDao faceDao;
    private final MetaDao metaDao;
    private final FaceImageSource imageSource;
    private final ConfigStore configStore;

    /**
     * Creates the migration with the production image source, which resolves
     * stored hashes to photo files and video frames.
     *
     * @param faceDao     face lookup and update; must not be null
     * @param metaDao     completion marker; must not be null
     * @param imageDao    photo path lookup; must not be null
     * @param videoDao    video and video frame lookup; must not be null
     * @param configStore application configuration; must not be null
     */
    public FaceRotationMigration(FaceDao faceDao, MetaDao metaDao, ImageDao imageDao,
                                 VideoDao videoDao, ConfigStore configStore) {
        this(faceDao, metaDao,
                new StoredFaceImageSource(imageDao, videoDao, FfmpegVideoFrameSource::new),
                configStore);
    }

    /**
     * Creates the migration against an explicit image source, so tests can
     * supply originals in memory.
     */
    FaceRotationMigration(FaceDao faceDao, MetaDao metaDao, FaceImageSource imageSource,
                          ConfigStore configStore) {
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao must not be null");
        this.metaDao = Objects.requireNonNull(metaDao, "metaDao must not be null");
        this.imageSource = Objects.requireNonNull(imageSource, "imageSource must not be null");
        this.configStore = Objects.requireNonNull(configStore, "configStore must not be null");
    }

    /**
     * Whether a pass already completed against this database.
     *
     * @return {@code true} when there is nothing left to do
     */
    public boolean isCompleted() throws SQLException {
        return metaDao.get(COMPLETION_KEY).isPresent();
    }

    /**
     * Straightens every stored face crop that is still rotated.
     *
     * <p>Faces are grouped by image so each original is read and each frame
     * decoded only once, however many faces it contains.</p>
     *
     * @param service   face detection and embedding service the pass runs on.
     *                  The caller must give the pass a service of its own: face
     *                  models are not thread-safe, and the shared service may be
     *                  in use by another part of the application while the pass
     *                  runs in the background
     * @param progress  receives the number of faces visited so far and the total
     *                  number of stored faces; must not be null
     * @param cancelled polled between faces; when it reports {@code true} the
     *                  pass stops early and is not recorded as complete
     * @return what the pass did
     * @throws SQLException when the database cannot be read or written
     */
    public Result run(FaceAiService service, ProgressListener progress, BooleanSupplier cancelled)
            throws SQLException {
        Objects.requireNonNull(service, "service must not be null");
        Objects.requireNonNull(progress, "progress must not be null");
        Objects.requireNonNull(cancelled, "cancelled must not be null");
        List<FaceDao.FaceBbox> storedFaces = faceDao.findAllBboxes();
        Map<String, List<FaceDao.FaceBbox>> facesByImage = groupByImage(storedFaces);
        int total = storedFaces.size();
        ConfigModel config = configStore.get();

        int processed = 0;
        int rotated = 0;
        int skipped = 0;
        int failed = 0;
        for (Map.Entry<String, List<FaceDao.FaceBbox>> entry : facesByImage.entrySet()) {
            if (cancelled.getAsBoolean()) {
                return new Result(total, rotated, skipped, failed, false);
            }
            BufferedImage original = loadOriginal(entry.getKey());
            List<DetectedFace> detections = original == null
                    ? List.of()
                    : FaceDetectionUtils.detectAllFaces(original, config.maxDetectionDimension(), service);
            for (FaceDao.FaceBbox stored : entry.getValue()) {
                processed++;
                progress.onProgress(processed, total);
                if (cancelled.getAsBoolean()) {
                    return new Result(total, rotated, skipped, failed, false);
                }
                int turns = original == null ? 0 : uprightTurnsFor(stored, detections);
                if (turns == 0) {
                    skipped++;
                    continue;
                }
                if (straighten(stored, original, turns, config, service)) {
                    rotated++;
                } else {
                    failed++;
                }
            }
        }
        metaDao.put(COMPLETION_KEY, Long.toString(System.currentTimeMillis()));
        return new Result(total, rotated, skipped, failed, true);
    }

    @Override
    public void close() {
        imageSource.close();
    }

    /**
     * Reads the original behind a stored hash, treating an unreadable source as
     * an absent one so a single missing file cannot abort the whole pass.
     */
    private BufferedImage loadOriginal(String imageHash) {
        try {
            return imageSource.load(imageHash);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read the original for face rotation migration: "
                    + imageHash, e);
            return null;
        }
    }

    /**
     * Returns how many quarter turns the stored crop has to be rotated by, or
     * {@code 0} when the stored face cannot be matched to a fresh detection.
     */
    private static int uprightTurnsFor(FaceDao.FaceBbox stored, List<DetectedFace> detections) {
        DetectedFace match = bestMatch(stored, detections);
        return match == null ? 0 : FaceRotation.uprightQuarterTurns(match);
    }

    /**
     * Re-crops the stored bounding box, rotates it upright, and replaces the
     * stored embedding and thumbnail. Returns {@code false} when any step fails.
     */
    private boolean straighten(FaceDao.FaceBbox stored, BufferedImage original, int turns,
                               ConfigModel config, FaceAiService service) {
        try {
            DetectedFace bounds = new DetectedFace(stored.x(), stored.y(),
                    stored.width(), stored.height(), 1.0f);
            BufferedImage upright = FaceRotation.rotateQuarterTurns(bounds.crop(original), turns);
            BufferedImage thumbnail = ImageUtils.downsize(upright, config.faceCropSize());
            float[] embedding = service.getEmbedding(thumbnail);
            byte[] subImageJpg = ImageUtils.toJpegBytes(thumbnail, config.thumbnailQuality());
            faceDao.updateSubImage(stored.id(), embedding, subImageJpg);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    "Could not straighten stored face " + stored.id(), e);
            return false;
        }
    }

    private static Map<String, List<FaceDao.FaceBbox>> groupByImage(List<FaceDao.FaceBbox> faces) {
        Map<String, List<FaceDao.FaceBbox>> byImage = new LinkedHashMap<>();
        for (FaceDao.FaceBbox face : faces) {
            byImage.computeIfAbsent(face.imageHash(), hash -> new ArrayList<>()).add(face);
        }
        return byImage;
    }

    /**
     * Returns the fresh detection that overlaps the stored bounding box most,
     * or {@code null} when none overlaps it enough to be the same face.
     */
    private static DetectedFace bestMatch(FaceDao.FaceBbox stored, List<DetectedFace> detections) {
        DetectedFace best = null;
        double bestIou = 0.0;
        for (DetectedFace candidate : detections) {
            double iou = intersectionOverUnion(stored, candidate);
            if (iou > bestIou) {
                bestIou = iou;
                best = candidate;
            }
        }
        return bestIou >= MIN_MATCH_IOU ? best : null;
    }

    /**
     * Intersection over union of a stored bounding box and a fresh detection.
     * Computed in {@code long} arithmetic so large coordinates cannot overflow.
     */
    static double intersectionOverUnion(FaceDao.FaceBbox stored, DetectedFace detected) {
        long left = Math.max(stored.x(), detected.x());
        long top = Math.max(stored.y(), detected.y());
        long right = Math.min((long) stored.x() + stored.width(),
                (long) detected.x() + detected.width());
        long bottom = Math.min((long) stored.y() + stored.height(),
                (long) detected.y() + detected.height());
        long intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
        if (intersection == 0) {
            return 0.0;
        }
        long union = (long) stored.width() * stored.height()
                + (long) detected.width() * detected.height()
                - intersection;
        return union <= 0 ? 0.0 : (double) intersection / union;
    }

    /**
     * Reports how far a pass has got.
     */
    @FunctionalInterface
    public interface ProgressListener {

        /**
         * @param processed faces visited so far
         * @param total     stored faces to visit
         */
        void onProgress(int processed, int total);
    }

    /**
     * Summary of one pass.
     *
     * @param total     stored faces the pass considered
     * @param rotated   faces whose crop was rotated upright and re-encoded
     * @param skipped   faces left untouched because no turn was needed, no
     *                  detection matched the stored bounding box, or the
     *                  original file could not be read
     * @param failed    faces whose crop could not be re-encoded
     * @param completed whether the pass reached the end and was recorded
     */
    public record Result(int total, int rotated, int skipped, int failed, boolean completed) {
    }
}
