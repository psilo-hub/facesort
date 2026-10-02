package free.svoss.facesort.db;

import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link DataRemovalDao} against an in-memory SQLite
 * database.
 */
class DataRemovalDaoTest {

    private Database db;
    private DataRemovalDao dao;
    private ImageDao imageDao;
    private VideoDao videoDao;
    private FaceDao faceDao;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        dao = new DataRemovalDao(db.getConnection());
        imageDao = new ImageDao(db.getConnection());
        videoDao = new VideoDao(db.getConnection());
        faceDao = new FaceDao(db.getConnection());
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    // ---- helpers ----

    private void insertImage(String hash) throws Exception {
        imageDao.insert(hash, 123L, "{}", 0);
    }

    private void addPhoto(String hash, String path) throws Exception {
        insertImage(hash);
        imageDao.addPath(hash, path);
    }

    private void addThumb(String hash) throws Exception {
        imageDao.saveThumbnail(hash, new byte[]{1, 2, 3});
    }

    private void addFace(String imageHash) throws Exception {
        faceDao.insert(new FaceRecord(0, imageHash, 0, 0, 10, 10, 0.9,
                new float[]{1.0f}, new byte[]{4, 5}, null));
    }

    private void addFace(String imageHash, int bboxOffset) throws Exception {
        faceDao.insert(new FaceRecord(0, imageHash, 0, bboxOffset, 10, 10, 0.9,
                new float[]{1.0f}, new byte[]{4, 5}, null));
    }

    private void insertVideo(String hash, String path) throws Exception {
        videoDao.insert(hash, 123L, "{}", 60.0);
        videoDao.addPath(hash, path);
    }

    private void linkFrame(String videoHash, String frameHash) throws Exception {
        videoDao.linkFrame(frameHash, videoHash, 0L);
    }

    // ---- counting ----

    @Test
    void countAffectedImages_matchesPhotosByPathPrefix() throws Exception {
        addPhoto("photo1", "/media/vacation/a.jpg");
        addPhoto("photo2", "/media/vacation/sub/b.jpg");
        addPhoto("photo3", "/media/work/c.jpg");
        addPhoto("photo4", "/media/other/d.jpg");

        assertEquals(2, dao.countAffectedImages("/media/vacation"),
                "photos whose stored path starts with the prefix must be counted");
        assertEquals(0, dao.countAffectedVideos("/media/vacation"));
    }

    @Test
    void countAffectedImages_matchesEachImageOnceWithSeveralPaths() throws Exception {
        insertImage("photo1");
        imageDao.addPath("photo1", "/media/vacation/a.jpg");
        imageDao.addPath("photo1", "/media/vacation/b.jpg");
        addPhoto("photo2", "/media/vacation/c.jpg");

        assertEquals(2, dao.countAffectedImages("/media/vacation"),
                "an image with multiple matching paths is counted once");
    }

    @Test
    void countAffectedVideos_matchesByVideoPathPrefix() throws Exception {
        insertVideo("video1", "/media/vacation/movie.mp4");
        insertVideo("video2", "/media/work/clip.mov");
        insertVideo("video3", "/media/other/other.mp4");

        assertEquals(1, dao.countAffectedVideos("/media/vacation"));
    }

    @Test
    void orphanFramesOfMatchingVideos_countedAsImages() throws Exception {
        insertVideo("video1", "/media/vacation/movie.mp4");
        insertVideo("video2", "/media/work/clip.mov");
        insertImage("frame1");
        insertImage("frame2");
        insertImage("frame3");
        linkFrame("video1", "frame1");
        linkFrame("video1", "frame2");
        linkFrame("video2", "frame3");

        assertEquals(2, dao.countAffectedImages("/media/vacation"),
                "frames sampled from a matching video are counted as images to remove");
    }

    @Test
    void framesThatAreAlsoPhotos_preservedWhenTheirOwnPathDoesNotMatch() throws Exception {
        insertVideo("video1", "/media/vacation/movie.mp4");
        // Frame that is also a photo stored elsewhere: must survive the video's removal.
        addPhoto("kept-frame", "/media/work/kept.jpg");
        linkFrame("video1", "kept-frame");
        // Frame that is also a photo stored under the prefix: counted as an image once.
        addPhoto("matched-frame", "/media/vacation/frame.jpg");
        linkFrame("video1", "matched-frame");

        assertEquals(1, dao.countAffectedImages("/media/vacation"),
                "a frame that is also a photo is removed only when its own path matches");
    }

    @Test
    void countAffectedThumbnailsAndFaces_coverOnlyAffectedImages() throws Exception {
        addPhoto("photo1", "/media/vacation/a.jpg");
        addThumb("photo1");
        addFace("photo1");
        addFace("photo1", 20);

        addPhoto("photo2", "/media/work/b.jpg");
        addThumb("photo2");
        addFace("photo2");

        insertVideo("video1", "/media/vacation/movie.mp4");
        insertImage("frame1");
        addThumb("frame1");
        linkFrame("video1", "frame1");

        assertEquals(2, dao.countAffectedThumbnails("/media/vacation"),
                "thumbnails of affected images (photo + orphan frame) are counted");
        assertEquals(2, dao.countAffectedFaces("/media/vacation"),
                "faces of affected images are counted");
    }

    @Test
    void prefixMatch_isAgainstTheStoredPathString() throws Exception {
        addPhoto("a", "/v/x.jpg");
        addPhoto("b", "/v2/x.jpg");

        assertEquals(2, dao.countAffectedImages("/v"),
                "the prefix is a plain string prefix, like the rest of the app");
        assertEquals(0, dao.countAffectedVideos("/v"));
    }

    @Test
    void prefixMatchesSiblingFoldersThatShareTheBeginning() throws Exception {
        addPhoto("in-folder", "/media/vacation/a.jpg");
        addPhoto("sibling", "/media/vacation-archive/b.jpg");
        addPhoto("just-below", "/media/vacatio/c.jpg");
        addPhoto("just-above", "/media/vacatiow/d.jpg");
        insertVideo("video-in-folder", "/media/vacation/movie.mp4");
        insertVideo("video-elsewhere", "/media/work/clip.mp4");

        assertEquals(2, dao.countAffectedImages("/media/vacation"),
                "a sibling folder whose name continues the prefix still matches, a shorter or a "
                        + "different one does not");
        assertEquals(1, dao.countAffectedVideos("/media/vacation"));

        assertEquals(2, dao.deleteAffectedImages("/media/vacation"),
                "the removal must delete exactly what the estimate counted");
        assertTrue(imageDao.exists("just-below"));
        assertTrue(imageDao.exists("just-above"));
    }

    @Test
    void prefixMatch_isCaseSensitive() throws Exception {
        addPhoto("lower", "/media/vacation/a.jpg");
        addPhoto("upper", "/Media/Vacation/b.jpg");

        assertEquals(1, dao.countAffectedImages("/media/vacation"),
                "the stored path is compared byte-wise, so a case difference is a different path");
        assertEquals(0, dao.countAffectedImages("/MEDIA/vacation"));
    }

    @Test
    void prefixMatch_worksOnPathsOutsideTheBasicMultilingualPlane() throws Exception {
        addPhoto("inside", "/photos/\uD83C\uDF05/a.jpg");
        addPhoto("also-inside", "/photos/\uD83C\uDF05/b.jpg");
        addPhoto("sibling", "/photos/\uD83C\uDF06/c.jpg");
        addPhoto("below", "/photos/\uD83C\uDF04/d.jpg");
        insertVideo("video-inside", "/photos/\uD83C\uDF05/movie.mp4");

        assertEquals(2, dao.countAffectedImages("/photos/\uD83C\uDF05"),
                "a prefix ending in a supplementary code point must bound the range correctly");
        assertEquals(1, dao.countAffectedVideos("/photos/\uD83C\uDF05"));
        assertEquals(1, dao.countAffectedImages("/photos/\uD83C\uDF04"),
                "the code point below must bound the range below it, not above its own folder");
    }

    @Test
    void prefixQueries_useThePathIndexesInsteadOfScanningThePathTables() throws Exception {
        PathPrefix filter = PathPrefix.of("/media/vacation");

        String affectedHashes = DataRemovalDao.affectedImageHashesSql(filter);
        QueryPlans.assertSearchedByIndex(
                QueryPlans.of(db.getConnection(), "SELECT COUNT(*) FROM (" + affectedHashes + ")"),
                affectedHashes, "ip", "vp");
        QueryPlans.assertSearchedByIndex(
                QueryPlans.of(db.getConnection(), DataRemovalDao.countAffectedVideosSql(filter)),
                DataRemovalDao.countAffectedVideosSql(filter), "video_paths");
        QueryPlans.assertSearchedByIndex(
                QueryPlans.of(db.getConnection(), DataRemovalDao.deleteAffectedVideosSql(filter)),
                DataRemovalDao.deleteAffectedVideosSql(filter), "video_paths");
    }

    // ---- deletion ----

    @Test
    void deleteAffectedImages_cascadesThumbnailsFacesAndFrameLinks() throws Exception {
        addPhoto("gone", "/media/vacation/a.jpg");
        addThumb("gone");
        addFace("gone");
        addPhoto("kept", "/media/work/b.jpg");
        addThumb("kept");

        insertVideo("video1", "/media/vacation/movie.mp4");
        insertImage("orphan-frame");
        linkFrame("video1", "orphan-frame");
        insertImage("plain-frame");
        linkFrame("video1", "plain-frame");

        int removed = dao.deleteAffectedImages("/media/vacation");

        assertEquals(3, removed, "matching photo + orphan frame + plain frame all removed");
        assertFalse(imageDao.exists("gone"), "matching photo must be deleted");
        assertFalse(imageDao.findByHash("gone").isPresent());
        assertFalse(imageDao.hasThumbnail("gone"), "thumbnail must cascade");
        assertTrue(faceDao.findByImageHash("gone").isEmpty(), "faces must cascade");
        assertFalse(imageDao.exists("orphan-frame"), "orphan frame must be deleted");
        assertFalse(imageDao.exists("plain-frame"), "linked frame image must be deleted");
        assertTrue(imageDao.exists("kept"), "non-matching photo must survive");
        assertTrue(imageDao.hasThumbnail("kept"));
        assertEquals(0, dao.countAffectedImages("/media/vacation"));
    }

    @Test
    void deleteAffectedVideos_cascadesVideoPathsAndFrameLinks() throws Exception {
        insertVideo("gone", "/media/vacation/movie.mp4");
        insertVideo("kept", "/media/work/clip.mov");
        insertImage("frame1");
        insertImage("frame2");
        linkFrame("gone", "frame1");
        linkFrame("kept", "frame2");

        int removed = dao.deleteAffectedVideos("/media/vacation");

        assertEquals(1, removed);
        assertFalse(videoDao.exists("gone"), "matching video must be deleted");
        assertTrue(videoDao.getPaths("gone").isEmpty(), "video paths must cascade");
        assertTrue(videoDao.findFramesForVideo("gone").isEmpty(), "frame links must cascade");
        assertTrue(videoDao.exists("kept"), "non-matching video must survive");
        assertEquals(1, videoDao.findFramesForVideo("kept").size());
        assertTrue(imageDao.exists("frame1"), "deleting a video alone keeps its frame images");
    }
}