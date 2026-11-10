package free.svoss.facesort.service;

import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Provides the data for the browse/view flow (View tab).
 *
 * <p>For every name the service computes a summary card (representative face,
 * face count) and, on demand, the distinct images containing faces with a
 * given name so the UI can present them as thumbnails. Opening an original
 * image in the operating system default viewer is also delegated here.</p>
 */
public final class ViewServiceImpl implements ViewService {

    private final FaceSelector faceSelector;
    private final FaceDao faceDao;
    private final NameDao nameDao;
    private final ImageDao imageDao;
    private final VideoDao videoDao;
    private final MediaFileResolver resolver;

    /**
     * Creates a view service.
     *
     * @param faceAiService face embedding/similarity service; must not be null
     * @param faceDao       DAO for the faces table; must not be null
     * @param nameDao       DAO for the names table; must not be null
     * @param imageDao      DAO for the images, image_paths and thumbnails tables; must not be null
     * @param videoDao      DAO for the videos, video_paths and video_frames tables; must not be null
     */
    public ViewServiceImpl(FaceAiService faceAiService, FaceDao faceDao,
                           NameDao nameDao, ImageDao imageDao, VideoDao videoDao) {
        Objects.requireNonNull(faceAiService, "faceAiService");
        this.faceSelector = new FaceSelector(faceAiService);
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
        this.nameDao = Objects.requireNonNull(nameDao, "nameDao");
        this.imageDao = Objects.requireNonNull(imageDao, "imageDao");
        this.videoDao = Objects.requireNonNull(videoDao, "videoDao");
        this.resolver = new MediaFileResolver(imageDao, videoDao);
    }

    /**
     * Returns a summary card for every name that has at least one tagged face.
     *
     * <p>The representative face is the face whose embedding is most similar to
     * the name's average embedding. Names without faces (or without usable
     * embeddings) are omitted.</p>
     *
     * <p>Three queries cover up to 500 names — the names, the embeddings of
     * every name's faces (loaded without the per-face crops the summary never
     * reads), and then the full rows of just the selected representatives,
     * whose crops the card renders.</p>
     *
     * @return summaries in name order, without names that have no faces
     * @throws SQLException on database access failure
     */
    @Override
    public List<ViewService.NameSummary> getNameSummaries() throws SQLException {
        List<NameRecord> names = nameDao.findAll();
        if (names.isEmpty()) {
            return List.of();
        }
        List<Long> nameIds = new ArrayList<>(names.size());
        for (NameRecord name : names) {
            nameIds.add(name.id());
        }
        Map<Long, List<FaceRecord>> facesByName = faceDao.findSlimByNameIds(nameIds);

        List<ViewService.NameSummary> summaries = new ArrayList<>(names.size());
        List<Long> representativeIds = new ArrayList<>(names.size());
        for (NameRecord name : names) {
            List<FaceRecord> faces = facesByName.get(name.id());
            if (faces.isEmpty()) {
                continue;
            }
            FaceRecord representative = faceSelector.representativeOf(faces);
            summaries.add(new ViewService.NameSummary(name, faces.size(), representative));
            representativeIds.add(representative.id());
        }

        Map<Long, FaceRecord> hydrated = new LinkedHashMap<>();
        for (FaceRecord face : faceDao.findNamedByIds(representativeIds)) {
            hydrated.put(face.id(), face);
        }
        List<ViewService.NameSummary> result = new ArrayList<>(summaries.size());
        for (ViewService.NameSummary summary : summaries) {
            FaceRecord full = hydrated.get(summary.representative().id());
            result.add(full == null
                    ? summary
                    : new ViewService.NameSummary(summary.name(), summary.faceCount(), full));
        }
        return result;
    }

    /**
     * Returns the distinct images that contain at least one face with the given
     * name, together with their stored thumbnails.
     *
     * <p>Two queries load the grid for up to 500 images — the distinct image
     * hashes, then all their thumbnails in one batched lookup — and only one
     * extra thumbnail query per further 500 is added, so the query count does
     * not grow with the number of images. Only the images are read, not the
     * faces: the grid shows image thumbnails, so the per-face embedding and
     * sub-image JPEG are never hydrated.</p>
     *
     * <p>Thumbnail data may be {@code null} for an image when none is stored
     * yet; the UI decides how to render that case.</p>
     *
     * @param nameId id of the name
     * @return distinct images in the order of each image's first tagged face
     * @throws SQLException on database access failure
     */
    @Override
    public List<ViewService.NamedImage> getImagesForName(long nameId) throws SQLException {
        List<String> hashes = faceDao.findImageHashesByNameId(nameId);
        Map<String, byte[]> thumbnails = imageDao.getThumbnails(hashes);
        List<ViewService.NamedImage> images = new ArrayList<>(hashes.size());
        for (String hash : hashes) {
            images.add(new ViewService.NamedImage(hash, thumbnails.get(hash)));
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
    @Override
    public int untagFacesFromImage(String imageHash, long nameId) throws SQLException {
        return faceDao.untagFacesFromImage(Objects.requireNonNull(imageHash, "imageHash"), nameId);
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
    @Override
    public boolean openOriginal(String hash) throws IOException, SQLException {
        return resolver.openInDefaultViewer(hash);
    }

    /**
     * Tells whether an original file for the given image still exists on disk.
     * For video frames this is the source video the frame came from.
     *
     * @param hash content hash of the image
     * @return {@code true} if at least one stored path exists on disk
     * @throws SQLException on database access failure
     */
    @Override
    public boolean isOriginalAvailable(String hash) throws SQLException {
        return resolver.resolveOriginalFile(hash).isPresent();
    }

    /**
     * Opens the folder that contains the original file of an image in the
     * operating system file manager. For video frames this is the folder of the
     * source video the frame came from.
     *
     * @param hash content hash of the image
     * @return {@code true} if the folder was handed to the file manager
     * @throws IOException  if the file manager cannot open the folder
     * @throws SQLException on database access failure
     */
    @Override
    public boolean openContainingFolder(String hash) throws IOException, SQLException {
        return resolver.openContainingFolder(hash);
    }

    /**
     * Tells whether the folder containing the original file for the given image
     * exists on disk and can be opened. For video frames this is the folder of
     * the source video the frame came from.
     *
     * @param hash content hash of the image
     * @return {@code true} if a containing folder can be opened
     * @throws SQLException on database access failure
     */
    @Override
    public boolean isContainingFolderAvailable(String hash) throws SQLException {
        return resolver.resolveContainingFolder(hash).isPresent();
    }
}
