package free.svoss.facesort.service;

import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for {@link ViewService}: browsing names to images, and
 * untagging faces from an image in the view grid.
 */
class ViewServiceTest {

    private Database db;
    private FaceDao faceDao;
    private NameDao nameDao;
    private ImageDao imageDao;
    private VideoDao videoDao;
    private ViewService service;
    private Path tempVideo;
    private final Set<String> insertedImages = new HashSet<>();
    private final AtomicInteger bbox = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        faceDao = new FaceDao(db.getConnection());
        nameDao = new NameDao(db.getConnection());
        imageDao = new ImageDao(db.getConnection());
        videoDao = new VideoDao(db.getConnection());
        service = new ViewService(new FaceAiService(new FakeFaceAiEngine()),
                faceDao, nameDao, imageDao, videoDao);
        tempVideo = Files.createTempFile("facesort-open-original-", ".mp4");
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempVideo);
    }

    private long addFace(String imageHash, float[] embedding, Long nameId) throws SQLException {
        if (insertedImages.add(imageHash)) {
            imageDao.insert(imageHash, 0, "{}", 1);
        }
        int offset = bbox.getAndAdd(20);
        return faceDao.insert(new FaceRecord(
                0, imageHash, offset, 0, 80, 80, 0.9, embedding, new byte[]{1}, nameId));
    }

    /**
     * Inserts a video-frame-shaped image (thumbnail stored, no photo path)
     * linked to a video, plus a face on it.
     */
    private long addVideoFrameFace(String imageHash, float[] embedding, Long nameId) throws SQLException {
        if (insertedImages.add(imageHash)) {
            imageDao.insert(imageHash, 0, "{}", 1);
            imageDao.saveThumbnail(imageHash, new byte[]{9, 8, 7});
            videoDao.insert("video-of-" + imageHash, 0L, "{}", 10.0);
            videoDao.addPath("video-of-" + imageHash, "/videos/sample.mp4");
            videoDao.linkFrame(imageHash, "video-of-" + imageHash, 1000L);
        }
        int offset = bbox.getAndAdd(20);
        return faceDao.insert(new FaceRecord(
                0, imageHash, offset, 0, 80, 80, 0.9, embedding, new byte[]{1}, nameId));
    }

    /**
     * Registers a frame image whose source video exists at {@code videoPath}.
     */
    private void addFrameLinkedToExistingVideo(String frameHash, String videoHash, Path videoPath)
            throws SQLException {
        imageDao.insert(frameHash, 0, "{}", 1);
        imageDao.saveThumbnail(frameHash, new byte[]{9, 8, 7});
        videoDao.insert(videoHash, 0L, "{}", 10.0);
        videoDao.addPath(videoHash, videoPath.toString());
        videoDao.linkFrame(frameHash, videoHash, 1000L);
    }

    @Test
    void untagFacesFromImage_clearsNameFromFacesOfThatImageOnly() throws SQLException {
        long alice = nameDao.insert("Alice");
        long bob = nameDao.insert("Bob");
        addFace("img1", new float[]{1, 0, 0, 0, 0, 0, 0, 0}, alice);
        addFace("img1", new float[]{1, 0, 0, 0, 0, 0, 0, 0}, alice);
        addFace("img1", new float[]{0, 1, 0, 0, 0, 0, 0, 0}, bob);
        addFace("img2", new float[]{1, 0, 0, 0, 0, 0, 0, 0}, alice);

        int untagged = service.untagFacesFromImage("img1", alice);

        assertEquals(2, untagged);
        assertEquals(1, faceDao.countByNameId(alice), "Alice face in img2 must stay tagged");
        assertEquals(1, faceDao.countByNameId(bob), "Bob's face must stay tagged");
        assertEquals(2, faceDao.findUnnamed().size());
    }

    @Test
    void untagFacesFromImage_makesImageDisappearFromNameImages() throws SQLException {
        long alice = nameDao.insert("Alice");
        addFace("img1", new float[]{1, 0, 0, 0, 0, 0, 0, 0}, alice);
        addFace("img2", new float[]{1, 0, 0, 0, 0, 0, 0, 0}, alice);

        List<ViewService.NamedImage> before = service.getImagesForName(alice);
        assertEquals(2, before.size());

        service.untagFacesFromImage("img1", alice);

        List<ViewService.NamedImage> after = service.getImagesForName(alice);
        assertEquals(1, after.size(), "untagged image must leave the view grid");
        assertEquals("img2", after.get(0).hash());
    }

    @Test
    void untagFacesFromImage_nullHashThrows() {
        assertThrows(NullPointerException.class, () -> service.untagFacesFromImage(null, 1L));
    }

    @Test
    void getImagesForName_returnsDistinctImages() throws SQLException {
        long alice = nameDao.insert("Alice");
        imageDao.insert("imgA", 0, "{}", 2);
        imageDao.insert("imgB", 0, "{}", 1);
        faceDao.insert(new FaceRecord(0, "imgA", 0, 0, 80, 80, 0.9,
                new float[]{1, 0, 0, 0, 0, 0, 0, 0}, new byte[]{1}, alice));
        faceDao.insert(new FaceRecord(0, "imgA", 50, 0, 80, 80, 0.9,
                new float[]{1, 0, 0, 0, 0, 0, 0, 0}, new byte[]{1}, alice));
        faceDao.insert(new FaceRecord(0, "imgB", 0, 0, 80, 80, 0.9,
                new float[]{1, 0, 0, 0, 0, 0, 0, 0}, new byte[]{1}, alice));

        List<ViewService.NamedImage> images = service.getImagesForName(alice);

        assertEquals(2, images.size(), "two faces in one image must yield one entry");
        assertTrue(images.stream().anyMatch(i -> "imgA".equals(i.hash())));
        assertTrue(images.stream().anyMatch(i -> "imgB".equals(i.hash())));
    }

    @Test
    void getImagesForName_returnsVideoFrameWithItsThumbnail() throws SQLException {
        long alice = nameDao.insert("Alice");
        addVideoFrameFace("frame1", new float[]{1, 0, 0, 0, 0, 0, 0, 0}, alice);

        List<ViewService.NamedImage> images = service.getImagesForName(alice);

        assertEquals(1, images.size(), "a tagged video frame must appear in the view grid");
        assertEquals("frame1", images.get(0).hash());
        assertArrayEquals(new byte[]{9, 8, 7}, images.get(0).thumbnailJpg(),
                "the frame thumbnail must be presented");
    }

    @Test
    void getNameSummaries_includesNameWhoseOnlyFacesAreVideoFrames() throws SQLException {
        long alice = nameDao.insert("Alice");
        addVideoFrameFace("frame1", new float[]{1, 0, 0, 0, 0, 0, 0, 0}, alice);

        List<ViewService.NameSummary> summaries = service.getNameSummaries();

        assertEquals(1, summaries.size(), "a name tagged only on video faces must get a card");
        assertEquals("Alice", summaries.get(0).name().name());
        assertEquals(1, summaries.get(0).faceCount());
        assertEquals("frame1", summaries.get(0).representative().imageHash());
    }

    @Test
    void isOriginalAvailable_videoFrameWhoseVideoIsMissingIsFalse() throws SQLException {
        long alice = nameDao.insert("Alice");
        addVideoFrameFace("frame1", new float[]{1, 0, 0, 0, 0, 0, 0, 0}, alice);

        assertFalse(service.isOriginalAvailable("frame1"),
                "a video frame whose source video is gone has no original to open");
    }

    @Test
    void openOriginal_videoFrameWhoseVideoIsMissingReturnsFalseGracefully() throws Exception {
        long alice = nameDao.insert("Alice");
        addVideoFrameFace("frame1", new float[]{1, 0, 0, 0, 0, 0, 0, 0}, alice);

        assertFalse(service.openOriginal("frame1"),
                "must report the original as unavailable without side effects");
    }

    @Test
    void resolveOriginalFile_videoFrameResolvesToTheLinkedVideoFile() throws Exception {
        addFrameLinkedToExistingVideo("frame1", "video1", tempVideo);

        Optional<Path> resolved = ViewService.resolveOriginalFile(imageDao, videoDao, "frame1");

        assertTrue(resolved.isPresent());
        assertEquals(tempVideo, resolved.get().toAbsolutePath(),
                "the source video, not the frame, must be the original file");
    }

    @Test
    void isOriginalAvailable_videoFrameWithExistingVideoIsTrue() throws Exception {
        addFrameLinkedToExistingVideo("frame1", "video1", tempVideo);

        assertTrue(service.isOriginalAvailable("frame1"),
                "a frame whose source video exists on disk must be openable");
    }

    @Test
    void resolveOriginalFile_plainImageResolvesToItsStoredPath() throws Exception {
        imageDao.insert("img1", 0, "{}", 1);
        imageDao.addPath("img1", tempVideo.toString());

        Optional<Path> resolved = ViewService.resolveOriginalFile(imageDao, videoDao, "img1");

        assertTrue(resolved.isPresent());
        assertEquals(tempVideo, resolved.get().toAbsolutePath(),
                "a plain photo must resolve to its stored image path");
    }

    @Test
    void resolveOriginalFile_missingVideoFallsBackToStoredFramePath() throws Exception {
        imageDao.insert("frame1", 0, "{}", 1);
        imageDao.addPath("frame1", tempVideo.toString());
        videoDao.insert("video1", 0L, "{}", 10.0);
        videoDao.addPath("video1", tempVideo.resolveSibling("missing.mp4").toString());
        videoDao.linkFrame("frame1", "video1", 500L);

        Optional<Path> resolved = ViewService.resolveOriginalFile(imageDao, videoDao, "frame1");

        assertTrue(resolved.isPresent());
        assertEquals(tempVideo, resolved.get().toAbsolutePath(),
                "a frame whose video vanished falls back to its stored frame path");
    }

    @Test
    void resolveFilterFolder_photoWithSinglePathReturnsItsFolder() throws Exception {
        imageDao.insert("img1", 0, "{}", 1);
        imageDao.addPath("img1", tempVideo.toString());

        Optional<Path> folder = ViewService.resolveFilterFolder(imageDao, videoDao, "img1");

        assertEquals(tempVideo.getParent(), folder.orElse(null),
                "the photo's folder must be returned for the path filter");
    }

    @Test
    void resolveFilterFolder_photoWithMultiplePathsPrefersAnExistingOne() throws Exception {
        imageDao.insert("img1", 0, "{}", 1);
        imageDao.addPath("img1", tempVideo.resolveSibling("missing-photo.jpg").toString());
        imageDao.addPath("img1", tempVideo.toString());

        Optional<Path> folder = ViewService.resolveFilterFolder(imageDao, videoDao, "img1");

        assertEquals(tempVideo.getParent(), folder.orElse(null),
                "an existing stored path must win over missing ones");
    }

    @Test
    void resolveFilterFolder_videoFrameReturnsTheVideoFolder() throws Exception {
        addFrameLinkedToExistingVideo("frame1", "video1", tempVideo);

        Optional<Path> folder = ViewService.resolveFilterFolder(imageDao, videoDao, "frame1");

        assertEquals(tempVideo.getParent(), folder.orElse(null),
                "a video frame must resolve to its source video's folder");
    }

    @Test
    void resolveFilterFolder_withoutAnyStoredPathReturnsEmpty() throws Exception {
        imageDao.insert("img1", 0, "{}", 1);

        assertTrue(ViewService.resolveFilterFolder(imageDao, videoDao, "img1").isEmpty());
    }
}