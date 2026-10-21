package free.svoss.facesort.service;

import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.VideoDao;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves stored media file paths to actual files on disk.
 *
 * <p>Shared by {@link ViewService}, {@link StoredFaceImageSource} and the
 * naming, deduplication and face-to-name services. Centralizes the logic for
 * finding the first existing path among several stored candidates, resolving
 * the "original" file behind an image (video source for frames, image itself
 * otherwise), resolving a path filter, and opening originals / containing
 * folders in the desktop.</p>
 */
public final class MediaFileResolver {

    private final ImageDao imageDao;
    private final VideoDao videoDao;

    /**
     * @param imageDao DAO for the images and image_paths tables; must not be null
     * @param videoDao DAO for the videos, video_paths and video_frames tables; must not be null
     */
    public MediaFileResolver(ImageDao imageDao, VideoDao videoDao) {
        this.imageDao = Objects.requireNonNull(imageDao, "imageDao");
        this.videoDao = Objects.requireNonNull(videoDao, "videoDao");
    }

    /**
     * Returns the first path in the list that still exists on disk.
     *
     * @param paths stored paths for an image, in priority order
     * @return the first existing path, or empty when none exists
     */
    public Optional<Path> firstExistingPath(List<String> paths) {
        for (String p : paths) {
            if (p == null || p.isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(p);
                if (Files.exists(path)) {
                    return Optional.of(path);
                }
            } catch (InvalidPathException ignored) {
                // a stored path that cannot be parsed is simply skipped
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the first path in the list that is readable on disk.
     *
     * @param paths stored paths for a media file, in priority order
     * @return the first readable path, or empty when none exists
     */
    public Optional<Path> firstReadablePath(List<String> paths) {
        for (String p : paths) {
            if (p == null || p.isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(p);
                if (Files.isReadable(path)) {
                    return Optional.of(path);
                }
            } catch (InvalidPathException ignored) {
                // a stored path that cannot be parsed is simply skipped
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the file that represents the "original" of an image: for a video
     * frame the source video, otherwise the image itself. The first still-existing
     * stored path is used; when the linked video file is gone the stored
     * frame/photo paths are considered as a fallback.
     *
     * @param imageHash content hash of the image; must not be null
     * @return the first stored path that still exists, if any
     * @throws NullPointerException if {@code imageHash} is null
     * @throws SQLException         on database access failure
     */
    public Optional<Path> resolveOriginalFile(String imageHash) throws SQLException {
        Objects.requireNonNull(imageHash, "imageHash");
        Optional<String> videoHash = videoDao.findVideoHash(imageHash);
        if (videoHash.isPresent()) {
            Optional<Path> video = firstExistingPath(videoDao.getPaths(videoHash.get()));
            if (video.isPresent()) {
                return video;
            }
        }
        return firstExistingPath(imageDao.getPaths(imageHash));
    }

    /**
     * Resolves the path to filter by for the media file behind an image: for a
     * video frame the full path of its source video (including the file name),
     * otherwise the folder of the image itself. When several paths are stored
     * the first one that still exists on disk is preferred, otherwise the first
     * recorded path is used.
     *
     * @param imageHash content hash of the image; must not be null
     * @return the path to filter by, or empty when no path is stored at all
     * @throws NullPointerException if {@code imageHash} is null
     * @throws SQLException         on database access failure
     */
    public Optional<Path> resolveFilterPath(String imageHash) throws SQLException {
        Objects.requireNonNull(imageHash, "imageHash");
        Optional<String> videoHash = videoDao.findVideoHash(imageHash);
        List<String> paths = videoHash.isPresent()
                ? videoDao.getPaths(videoHash.get())
                : imageDao.getPaths(imageHash);
        if (paths.isEmpty()) {
            return Optional.empty();
        }
        Path chosen = firstExistingPath(paths).orElseGet(() -> firstParsedPath(paths));
        if (chosen == null) {
            return Optional.empty();
        }
        return videoHash.isPresent()
                ? Optional.of(chosen)
                : Optional.ofNullable(chosen.getParent());
    }

    private Path firstParsedPath(List<String> paths) {
        for (String p : paths) {
            if (p == null || p.isBlank()) {
                continue;
            }
            try {
                return Path.of(p);
            } catch (InvalidPathException ignored) {
                // keep trying the next stored path
            }
        }
        return null;
    }

    /**
     * Opens the original file of an image — or, for a video frame, its source
     * video — in the operating system default viewer.
     *
     * <p>Returns {@code false} without side effects when no stored path exists
     * on disk or when the desktop platform does not support opening files.</p>
     *
     * @param imageHash content hash of the image
     * @return {@code true} if the file was handed to the default viewer
     * @throws IOException  if the default viewer cannot open the file
     * @throws SQLException on database access failure
     */
    public boolean openInDefaultViewer(String imageHash)
            throws IOException, SQLException {
        Optional<Path> existing = resolveOriginalFile(imageHash);
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
     * Resolves the folder that contains the original media file behind an
     * image: for a video frame the folder of its source video, otherwise the
     * folder of the image itself. Only folders of files that still exist on
     * disk are returned.
     *
     * @param imageHash content hash of the image
     * @return the containing folder, or empty when no original exists on disk
     * @throws SQLException on database access failure
     */
    public Optional<Path> resolveContainingFolder(String imageHash) throws SQLException {
        return resolveOriginalFile(imageHash).map(Path::getParent);
    }

    /**
     * Opens the folder that contains the original media file behind an image —
     * or, for a video frame, the folder of its source video — in the operating
     * system file manager.
     *
     * <p>Returns {@code false} without side effects when no original file
     * exists on disk or when the desktop platform does not support opening
     * folders.</p>
     *
     * @param imageHash content hash of the image
     * @return {@code true} if the folder was handed to the file manager
     * @throws IOException  if the file manager cannot open the folder
     * @throws SQLException on database access failure
     */
    public boolean openContainingFolder(String imageHash)
            throws IOException, SQLException {
        Optional<Path> original = resolveOriginalFile(imageHash);
        if (original.isEmpty()) {
            return false;
        }
        Path folder = original.get().getParent();
        if (folder == null
                || !Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            return false;
        }
        Desktop.getDesktop().open(folder.toFile());
        return true;
    }
}
