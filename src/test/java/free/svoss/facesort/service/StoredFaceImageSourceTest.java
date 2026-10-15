package free.svoss.facesort.service;

import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.util.ImageUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises {@link StoredFaceImageSource}: resolving a stored hash back to the
 * photo file it came from, falling back to the video frame for hashes that have
 * no photo, and the forward-only reuse of an open video source.
 */
class StoredFaceImageSourceTest {

    private static final double[] FRAME_POSITIONS = {0.5, 1.0, 1.5, 2.0, 2.5, 3.0};

    @TempDir
    Path tempDir;

    private Database db;
    private ImageDao imageDao;
    private VideoDao videoDao;
    private RecordingOpener opener;
    private StoredFaceImageSource source;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        imageDao = new ImageDao(db.getConnection());
        videoDao = new VideoDao(db.getConnection());
        opener = new RecordingOpener();
        source = new StoredFaceImageSource(imageDao, videoDao, opener);
    }

    @AfterEach
    void tearDown() throws Exception {
        source.close();
        db.close();
    }

    @Test
    void load_readsTheStoredPhotoFile() throws Exception {
        Path photo = writePhoto("photo.jpg", 12, 7);
        imageDao.insert("photoHash", 1L, "{}", 1);
        imageDao.addPath("photoHash", photo.toString());

        BufferedImage loaded = source.load("photoHash");

        assertNotNull(loaded, "a reachable photo must be reloaded from disk");
        assertEquals(12, loaded.getWidth());
        assertEquals(7, loaded.getHeight());
        assertEquals(0, opener.openCount, "a photo must not open a video");
    }

    @Test
    void load_skipsAPathThatIsGoneAndUsesTheNextOne() throws Exception {
        Path photo = writePhoto("photo.jpg", 9, 9);
        imageDao.insert("photoHash", 1L, "{}", 1);
        imageDao.addPath("photoHash", tempDir.resolve("gone.jpg").toString());
        imageDao.addPath("photoHash", photo.toString());

        BufferedImage loaded = source.load("photoHash");

        assertNotNull(loaded, "a missing path must be skipped rather than failing the load");
        assertEquals(9, loaded.getWidth());
    }

    @Test
    void load_decodesTheVideoFrameWhenNoPhotoResolves() throws Exception {
        String videoHash = storeVideoWithFrame("frameHash", 1000L);

        BufferedImage loaded = source.load("frameHash");

        assertNotNull(loaded, "a frame hash must be decoded from its video");
        assertEquals(FakeVideoFrameSource.frameColor(1.0).getRGB() & 0xFFFFFF,
                loaded.getRGB(0, 0) & 0xFFFFFF,
                "the frame at the stored timestamp must be delivered");
        assertEquals(1, opener.openCount);
        assertEquals(List.of(videoFile(videoHash)), opener.openedFiles,
                "the video's stored path must be the one that is opened");
    }

    @Test
    void load_returnsNullWhenNeitherAPhotoNorAFrameResolves() throws Exception {
        assertNull(source.load("unknown"), "a hash with no source must not be decoded");
        assertEquals(0, opener.openCount);
    }

    @Test
    void load_returnsNullWhenTheFrameIsPastTheEndOfTheVideo() throws Exception {
        storeVideoWithFrame("frameHash", 9000L);

        assertNull(source.load("frameHash"),
                "a timestamp beyond the video must not be reported as an image");
    }

    @Test
    void load_reusesTheOpenVideoWhileThePositionsMoveForward() throws Exception {
        String videoHash = storeVideoWithFrame("first", 1000L);
        storeFrame("second", videoHash, 2000L);

        source.load("first");
        source.load("second");

        assertEquals(1, opener.openCount,
                "consecutive frames of one video must be decoded by a single pass");
    }

    @Test
    void load_reopensTheVideoWhenThePositionGoesBackwards() throws Exception {
        String videoHash = storeVideoWithFrame("late", 2000L);
        storeFrame("early", videoHash, 1000L);

        source.load("late");
        source.load("early");

        assertEquals(2, opener.openCount,
                "a frame source cannot seek backwards, so it has to be reopened");
    }

    @Test
    void close_releasesTheOpenVideo() throws Exception {
        storeVideoWithFrame("frameHash", 1000L);
        source.load("frameHash");
        VideoFrameSource open = opener.opened.get(0);

        source.close();

        assertThrows(IllegalStateException.class, () -> open.seekTo(2.0),
                "closing the image source must close the video it kept open");
    }

    private Path writePhoto(String name, int width, int height) throws Exception {
        Path file = tempDir.resolve(name);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Files.write(file, ImageUtils.toJpegBytes(image, 0.9f));
        return file;
    }

    /** Stores a video with one readable path and links one frame at {@code timestampMs}. */
    private String storeVideoWithFrame(String frameHash, long timestampMs) throws Exception {
        String videoHash = "videoHash";
        Path file = videoFile(videoHash);
        Files.write(file, new byte[]{0});
        videoDao.insert(videoHash, 1L, "{}", 10.0);
        videoDao.addPath(videoHash, file.toString());
        storeFrame(frameHash, videoHash, timestampMs);
        return videoHash;
    }

    private void storeFrame(String frameHash, String videoHash, long timestampMs) throws Exception {
        imageDao.insert(frameHash, timestampMs, "{}", 0);
        videoDao.linkFrame(frameHash, videoHash, timestampMs);
    }

    private Path videoFile(String videoHash) {
        return tempDir.resolve(videoHash + ".mp4");
    }

    /**
     * Opener that hands out a fresh in-memory frame source per call and records
     * how often (and for which file) a video was opened.
     */
    private static final class RecordingOpener implements VideoFrameSourceOpener {

        private int openCount;
        private final List<Path> openedFiles = new ArrayList<>();
        private final List<VideoFrameSource> opened = new ArrayList<>();

        @Override
        public VideoFrameSource open(Path file) {
            openCount++;
            openedFiles.add(file);
            VideoFrameSource frameSource = new FakeVideoFrameSource(10.0, FRAME_POSITIONS);
            opened.add(frameSource);
            return frameSource;
        }
    }
}
