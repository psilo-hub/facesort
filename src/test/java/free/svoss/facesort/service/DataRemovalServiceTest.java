package free.svoss.facesort.service;

import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.DataRemovalDao;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link DataRemovalService} against an in-memory
 * SQLite database.
 */
class DataRemovalServiceTest {

    private Database db;
    private ImageDao imageDao;
    private VideoDao videoDao;
    private FaceDao faceDao;
    private DataRemovalService service;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        imageDao = new ImageDao(db.getConnection());
        videoDao = new VideoDao(db.getConnection());
        faceDao = new FaceDao(db.getConnection());
        DataRemovalDao removalDao = new DataRemovalDao(db.getConnection());
        service = new DataRemovalService(removalDao, db.getTransactionRunner());
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private void addPhoto(String hash, String path) throws Exception {
        imageDao.insert(hash, 123L, "{}", 0);
        imageDao.addPath(hash, path);
        imageDao.saveThumbnail(hash, new byte[]{1, 2, 3});
        faceDao.insert(new FaceRecord(0, hash, 0, 0, 10, 10, 0.9,
                new float[]{1.0f}, new byte[]{4, 5}, null));
    }

    private void addVideo(String hash, String path, String... frameHashes) throws Exception {
        videoDao.insert(hash, 123L, "{}", 60.0);
        videoDao.addPath(hash, path);
        for (String frameHash : frameHashes) {
            if (!imageDao.exists(frameHash)) {
                imageDao.insert(frameHash, 123L, "{}", 0);
            }
            videoDao.linkFrame(frameHash, hash, 0L);
        }
    }

    // ---- estimate ----

    @Test
    void estimate_countsMatchingPhotosVideosThumbnailsAndFaceSubImages() throws Exception {
        addPhoto("photo1", "/media/vacation/a.jpg");
        addPhoto("photo2", "/media/vacation/sub/b.jpg");
        addPhoto("photo3", "/media/work/c.jpg");
        addVideo("video1", "/media/vacation/movie.mp4", "frame1", "frame2");

        DataRemovalService.Estimate estimate = service.estimate("/media/vacation");

        assertEquals(4, estimate.images(), "2 matching photos + 2 orphan frames of the matching video");
        assertEquals(1, estimate.videos());
        assertEquals(2, estimate.thumbnails(), "thumbnail of each matching photo; frames have none");
        assertEquals(2, estimate.faceSubImages(), "one face per matching photo");
    }

    @Test
    void estimate_blankOrNullPrefix_matchesNothing() throws Exception {
        addPhoto("photo1", "/media/vacation/a.jpg");
        addVideo("video1", "/media/vacation/movie.mp4", "frame1");

        assertEquals(new DataRemovalService.Estimate(0, 0, 0, 0),
                service.estimate("   "));
        assertEquals(new DataRemovalService.Estimate(0, 0, 0, 0),
                service.estimate(null));
    }

    @Test
    void estimate_leavesFramesThatAreAlsoPhotosOutWhenTheirPathDoesNotMatch() throws Exception {
        addPhoto("photo1", "/media/work/kept.jpg");
        addVideo("video1", "/media/vacation/movie.mp4", "photo1");

        DataRemovalService.Estimate estimate = service.estimate("/media/vacation");

        assertEquals(0, estimate.images(),
                "a frame that is also a photo under a different path is preserved");
        assertEquals(1, estimate.videos());
        assertEquals(0, estimate.thumbnails());
        assertEquals(0, estimate.faceSubImages());
    }

    // ---- remove ----

    @Test
    void remove_deletesMatchingDataAndKeepsTheRest() throws Exception {
        addPhoto("gone", "/media/vacation/a.jpg");
        addPhoto("kept", "/media/work/b.jpg");
        addVideo("gone-video", "/media/vacation/movie.mp4", "orphan-frame");
        addVideo("kept-video", "/media/work/clip.mov", "kept-frame");

        DataRemovalService.Removal removal = service.remove("/media/vacation");

        assertEquals(new DataRemovalService.Removal(2, 1, 1, 1), removal,
                "matching photo + orphan frame; the matching video; that photo's thumbnail and face");
        assertFalse(imageDao.exists("gone"), "matching image must be gone");
        assertFalse(imageDao.exists("orphan-frame"), "orphan frame of a matching video must be gone");
        assertFalse(videoDao.exists("gone-video"), "matching video must be gone");
        assertTrue(imageDao.exists("kept"), "non-matching image must survive");
        assertTrue(imageDao.exists("kept-frame"), "frame of a non-matching video must survive");
        assertTrue(videoDao.exists("kept-video"), "non-matching video must survive");
        assertEquals(0, service.estimate("/media/vacation").images());
        assertEquals(0, service.estimate("/media/vacation").videos());
    }

    @Test
    void remove_isNoOpForBlankPrefix() throws Exception {
        addPhoto("photo1", "/media/vacation/a.jpg");
        addVideo("video1", "/media/vacation/movie.mp4", "frame1");

        assertEquals(new DataRemovalService.Removal(0, 0, 0, 0), service.remove("  "));

        assertTrue(imageDao.exists("photo1"));
        assertTrue(videoDao.exists("video1"));
    }
}