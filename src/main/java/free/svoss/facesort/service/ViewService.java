package free.svoss.facesort.service;

import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Provides the data for the browse/view flow (View tab).
 *
 * <p>For every name the service computes a summary card (representative face,
 * face count) and, on demand, the distinct images containing faces with a given
 * name so the UI can present them as thumbnails. Opening an original image in
 * the operating system default viewer is also delegated here.</p>
 */
public class ViewService {

    private final FaceAiService faceAiService;
    private final FaceDao faceDao;
    private final NameDao nameDao;
    private final ImageDao imageDao;
    private final VideoDao videoDao;

    /**
     * Creates a view service.
     *
     * @param faceAiService face embedding/similarity service; must not be null
     * @param faceDao       DAO for the faces table; must not be null
     * @param nameDao       DAO for the names table; must not be null
     * @param imageDao      DAO for the images, image_paths and thumbnails tables; must not be null
     * @param videoDao      DAO for the videos, video_paths and video_frames tables; must not be null
     */
    public ViewService(FaceAiService faceAiService, FaceDao faceDao,
                       NameDao nameDao, ImageDao imageDao, VideoDao videoDao) {
        this.faceAiService = Objects.requireNonNull(faceAiService, "faceAiService");
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
        this.nameDao = Objects.requireNonNull(nameDao, "nameDao");
        this.imageDao = Objects.requireNonNull(imageDao, "imageDao");
        this.videoDao = Objects.requireNonNull(videoDao, "videoDao");
    }

    /**
     * Returns a summary card for every name that has at least one named face.
     *
     * <p>The representative face is the face whose embedding is most similar to
     * the name's average embedding. Names without faces (or without usable
     * embeddings) are omitted.</p>
     *
     * @return summaries in name order, without names that have no faces
     * @throws SQLException on database access failure
     */
    public List<NameSummary> getNameSummaries() throws SQLException {
        List<NameSummary> summaries = new ArrayList<>();
        for (NameRecord name : nameDao.findAll()) {
            List<FaceRecord> faces = faceDao.findByNameId(name.id());
            List<float[]> embeddings = new ArrayList<>();
            for (FaceRecord face : faces) {
                if (face.embedding() != null) {
                    embeddings.add(face.embedding());
                }
            }
            if (embeddings.isEmpty()) {
                continue;
            }
            float[] average = faceAiService.calcAverage(embeddings);
            FaceRecord representative = findRepresentative(faces, average);
            summaries.add(new NameSummary(name, faces.size(), representative));
        }
        return summaries;
    }

    /**
     * Returns the distinct images that contain at least one face with the given
     * name, together with their stored thumbnails.
     *
     * <p>Thumbnail data may be {@code null} for an image when none is stored yet;
     * the UI decides how to render that case.</p>
     *
     * @param nameId id of the name
     * @return distinct images in the order the faces were found
     * @throws SQLException on database access failure
     */
    public List<NamedImage> getImagesForName(long nameId) throws SQLException {
        List<FaceRecord> faces = faceDao.findByNameId(nameId);
        Set<String> hashes = new LinkedHashSet<>();
        for (FaceRecord face : faces) {
            hashes.add(face.imageHash());
        }
        List<NamedImage> images = new ArrayList<>(hashes.size());
        for (String hash : hashes) {
            images.add(new NamedImage(hash, imageDao.getThumbnail(hash)));
        }
        return images;
    }

    /**
     * Removes the given name from every face of the given image, leaving those
     * faces unnamed again.
     *
     * @param imageHash content hash of the image; must not be null
     * @param nameId    the name to remove from the image's faces
     * @return the number of faces that were untagged
     * @throws NullPointerException if {@code imageHash} is null
     * @throws SQLException         on database access failure
     */
    public int untagFacesFromImage(String imageHash, long nameId) throws SQLException {
        return faceDao.untagFacesFromImage(Objects.requireNonNull(imageHash, "imageHash"), nameId);
    }

    /**
     * Resolves the file that represents the "original" of an image: for a video
     * frame the source video, otherwise the image itself. The first still-existing
     * stored path is used; when the linked video file is gone the stored
     * frame/photo paths are considered as a fallback.
     *
     * @param imageDao  DAO for the images and image_paths tables
     * @param videoDao  DAO for the videos, video_paths and video_frames tables
     * @param imageHash content hash of the image
     * @return the first stored path that still exists, if any
     * @throws SQLException on database access failure
     */
    public static Optional<Path> resolveOriginalFile(
            ImageDao imageDao, VideoDao videoDao, String imageHash) throws SQLException {
        Objects.requireNonNull(imageDao, "imageDao");
        Objects.requireNonNull(videoDao, "videoDao");
        Optional<String> videoHash = videoDao.findVideoHash(
                Objects.requireNonNull(imageHash, "imageHash"));
        if (videoHash.isPresent()) {
            Optional<Path> video = firstExistingPath(videoDao.getPaths(videoHash.get()));
            if (video.isPresent()) {
                return video;
            }
        }
        return firstExistingPath(imageDao.getPaths(imageHash));
    }

    /**
     * Opens the original file of an image — or, for a video frame, its source
     * video — in the operating system default viewer.
     *
     * <p>Returns {@code false} without side effects when no stored path exists
     * on disk or when the desktop platform does not support opening files.</p>
     *
     * @param imageDao  DAO for the images and image_paths tables
     * @param videoDao  DAO for the videos, video_paths and video_frames tables
     * @param imageHash content hash of the image
     * @return {@code true} if the file was handed to the default viewer
     * @throws IOException  if the default viewer cannot open the file
     * @throws SQLException on database access failure
     */
    public static boolean openInDefaultViewer(
            ImageDao imageDao, VideoDao videoDao, String imageHash)
            throws IOException, SQLException {
        Optional<Path> existing = resolveOriginalFile(imageDao, videoDao, imageHash);
        if (existing.isEmpty()) {
            return false;
        }
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            return false;
        }
        Desktop.getDesktop().open(existing.get().toFile());
        return true;
    }

    /**
     * Opens the original file of an image in the operating system default
     * viewer. For video frames this is the source video the frame came from.
     *
     * @param hash content hash of the image
     * @return {@code true} if the file was handed to the default viewer
     * @throws IOException  if the default viewer cannot open the file
     * @throws SQLException on database access failure
     */
    public boolean openOriginal(String hash) throws IOException, SQLException {
        return openInDefaultViewer(imageDao, videoDao, hash);
    }

    /**
     * Tells whether an original file for the given image still exists on disk.
     * For video frames this is the source video the frame came from.
     *
     * @param hash content hash of the image
     * @return {@code true} if at least one stored path exists on disk
     * @throws SQLException on database access failure
     */
    public boolean isOriginalAvailable(String hash) throws SQLException {
        return resolveOriginalFile(imageDao, videoDao, hash).isPresent();
    }

    /**
     * Returns the first path in the list that still exists on disk.
     *
     * <p>Package-private for unit testing.</p>
     *
     * @param paths stored paths for an image, in priority order
     * @return the first existing path, or empty when none exists
     */
    static Optional<Path> firstExistingPath(List<String> paths) {
        for (String p : paths) {
            Path path = Path.of(p);
            if (Files.exists(path)) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    /**
     * Selects the face whose embedding is most similar to the given average.
     *
     * <p>Faces without an embedding are skipped. The caller guarantees at least
     * one face has an embedding, so the result is never null in practice.</p>
     */
    private FaceRecord findRepresentative(List<FaceRecord> faces, float[] average) {
        FaceRecord best = null;
        double bestScore = -1.0;
        for (FaceRecord face : faces) {
            if (face.embedding() == null) {
                continue;
            }
            double score = faceAiService.calcSimilarity(average, face.embedding());
            if (best == null || score > bestScore) {
                best = face;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Summary card for one name in the view grid.
     *
     * @param name           the name
     * @param faceCount      number of faces tagged with the name
     * @param representative the face closest to the name's average embedding
     */
    public record NameSummary(NameRecord name, int faceCount, FaceRecord representative) {
    }

    /**
     * A single image containing at least one face with a given name.
     *
     * @param hash         content hash of the image
     * @param thumbnailJpg stored JPEG thumbnail, or {@code null} when absent
     */
    public record NamedImage(String hash, byte[] thumbnailJpg) {
    }
}