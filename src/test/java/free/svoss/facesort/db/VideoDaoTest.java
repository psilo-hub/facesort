package free.svoss.facesort.db;

import free.svoss.facesort.model.VideoFrameLinkRecord;
import free.svoss.facesort.model.VideoRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link VideoDao} against an in-memory SQLite database.
 */
class VideoDaoTest {

    private Database db;
    private VideoDao dao;
    private ImageDao imageDao;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        dao = new VideoDao(db.getConnection());
        imageDao = new ImageDao(db.getConnection());
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    // ---- helpers ----

    private void insertVideo(String hash, double durationSecs) throws Exception {
        dao.insert(hash, 123L, "{}", durationSecs);
    }

    /** A frame is an image row; register it so video_frames.frame_hash FK resolves. */
    private void insertFrameImage(String frameHash) throws Exception {
        imageDao.insert(frameHash, 123L, "{}", 1);
    }

    /** Inserts a video row and links one of its frames at the given timestamp. */
    private void insertVideoWithFrame(String videoHash, double durationSecs,
                                      String frameHash, long timestampMs) throws Exception {
        insertVideo(videoHash, durationSecs);
        insertFrameImage(frameHash);
        dao.linkFrame(frameHash, videoHash, timestampMs);
    }

    // ---- tests ----

    @Test
    void insert_exists_findByHash_roundTrip() throws Exception {
        insertVideo("video1", 12.5);

        assertTrue(dao.exists("video1"), "inserted video must exist");
        assertFalse(dao.exists("missing"), "unknown hash must not exist");

        VideoRecord video = dao.findByHash("video1").orElseThrow();
        assertEquals("video1", video.hash());
        assertEquals(12.5, video.durationSecs(), 0.001);
        assertEquals("{}", video.criteriaJson());
        assertEquals(0, video.frameCount(), "no linked frames yet");
        assertEquals(0, video.faceCount(), "no faces yet");
    }

    @Test
    void findByHash_missing_returnsEmpty() throws Exception {
        assertTrue(dao.findByHash("missing").isEmpty());
    }

    @Test
    void addPath_getPaths_dedupeKnownPath() throws Exception {
        insertVideo("video1", 5.0);
        dao.addPath("video1", "/videos/a.mp4");
        dao.addPath("video1", "/videos/b.mp4");
        dao.addPath("video1", "/videos/a.mp4"); // duplicate path -> ignored

        List<String> paths = dao.getPaths("video1");
        assertEquals(2, paths.size(), "duplicate path insert must be ignored");
        assertTrue(paths.contains("/videos/a.mp4"));
        assertTrue(paths.contains("/videos/b.mp4"));

        assertTrue(dao.hasPath("video1", "/videos/a.mp4"), "known path must be detected");
        assertFalse(dao.hasPath("video1", "/videos/nope.mp4"), "unknown path must not be detected");
    }

    @Test
    void getPaths_unknownVideo_returnsEmpty() throws Exception {
        assertTrue(dao.getPaths("missing").isEmpty());
        assertFalse(dao.hasPath("missing", "/x.mp4"));
    }

    // ---- frame links ----

    @Test
    void linkFrame_findFramesForVideo_findTimestamp() throws Exception {
        insertVideoWithFrame("video1", 60.0, "frame1", 1000L);
        insertVideoWithFrame("video1", 60.0, "frame2", 2000L);

        List<VideoFrameLinkRecord> frames = dao.findFramesForVideo("video1");
        assertEquals(2, frames.size(), "both frames must be linked");
        assertEquals(1000L, dao.findTimestamp("frame1"), "frame timestamp must be retrievable");
        assertNull(dao.findTimestamp("missing"), "unknown frame -> null");

        assertEquals(2, dao.findByHash("video1").orElseThrow().frameCount(),
                "frame_count column must track linked frames");
    }

    @Test
    void linkFrame_sameFrame_ignored_uniqueness() throws Exception {
        dao.insert("video1", 0L, "{}", 60.0);
        dao.insert("video2", 0L, "{}", 60.0);
        insertFrameImage("frame1");

        dao.linkFrame("frame1", "video1", 0L);
        dao.linkFrame("frame1", "video2", 0L); // same frame -> second link must be ignored

        assertEquals(1, dao.findFramesForVideo("video1").size(),
                "frame may be linked to at most one video");
        assertEquals(0, dao.findFramesForVideo("video2").size(),
                "the second link attempt must be dropped");
    }
}
