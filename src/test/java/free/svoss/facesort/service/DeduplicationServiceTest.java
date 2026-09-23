package free.svoss.facesort.service;

import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.db.NotDupeDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for {@link DeduplicationService} over an in-memory
 * database, using identical face embeddings so name pairs rank at ~1.0.
 */
class DeduplicationServiceTest {

    private Database db;
    private FaceDao faceDao;
    private NameDao nameDao;
    private NotDupeDao notDupeDao;
    private DeduplicationService service;

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.inMemory();
        faceDao = new FaceDao(db.getConnection());
        nameDao = new NameDao(db.getConnection());
        notDupeDao = new NotDupeDao(db.getConnection());
        service = new DeduplicationService(
                new FaceAiService(new FakeFaceAiEngine()), faceDao, nameDao, notDupeDao,
                new ImageDao(db.getConnection()), new VideoDao(db.getConnection()),
                db.getTransactionRunner());
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    private long addName(String name) throws SQLException {
        return nameDao.insert(name);
    }

    private void addFace(String imageHash, long nameId, float[] embedding) throws SQLException {
        new ImageDao(db.getConnection()).insert(imageHash, 0, "{}", 1);
        faceDao.insert(new FaceRecord(
                0, imageHash, 10, 10, 80, 80, 0.9, embedding, new byte[]{1}, nameId));
    }

    private static float[] identical() {
        return new float[]{1, 0, 0, 0, 0, 0, 0, 0};
    }

    @Test
    void nextPair_returnsSimilarNamePair() throws SQLException {
        long alice = addName("Alice");
        long bob = addName("Bob");
        addFace("imgA1", alice, identical());
        addFace("imgA2", alice, identical());
        addFace("imgB1", bob, identical());

        Optional<DeduplicationService.DupeCandidate> pair = service.nextPair();

        assertTrue(pair.isPresent());
        assertEquals(Set.of(alice, bob), Set.of(pair.get().nameIdA(), pair.get().nameIdB()));
        assertEquals(1.0, pair.get().similarity(), 1e-6);
        // only one comparable pair exists; the queue is then exhausted
        assertTrue(service.nextPair().isEmpty());
    }

    @Test
    void nextPair_excludesNamesWithoutFaces() throws SQLException {
        long alice = addName("Alice");
        long bob = addName("Bob");
        addFace("imgA1", alice, identical());
        addFace("imgB1", bob, identical());
        addName("Ghost"); // no faces -> cannot be compared

        Optional<DeduplicationService.DupeCandidate> pair = service.nextPair();

        assertTrue(pair.isPresent());
        assertEquals("Alice", pair.get().nameA());
        assertEquals("Bob", pair.get().nameB());
    }

    @Test
    void nextPair_ranksNamesWhoseFacesAreVideoFrames() throws SQLException {
        long alice = addName("Alice");
        long bob = addName("Bob");
        ImageDao imageDao = new ImageDao(db.getConnection());
        VideoDao videoDao = new VideoDao(db.getConnection());
        videoDao.insert("videoA", 0L, "{}", 10.0);
        for (int i = 0; i < 2; i++) {
            String frame = "vframe" + i;
            imageDao.insert(frame, 0, "{}", 1);
            imageDao.saveThumbnail(frame, new byte[]{1});
            videoDao.linkFrame(frame, "videoA", 1000L * i);
            faceDao.insert(new FaceRecord(0, frame, 10, 10, 80, 80, 0.9,
                    identical(), new byte[]{1}, alice));
        }
        imageDao.insert("vframeB", 0, "{}", 1);
        imageDao.saveThumbnail("vframeB", new byte[]{1});
        videoDao.linkFrame("vframeB", "videoA", 3000L);
        faceDao.insert(new FaceRecord(0, "vframeB", 10, 10, 80, 80, 0.9,
                identical(), new byte[]{1}, bob));

        Optional<DeduplicationService.DupeCandidate> pair = service.nextPair();

        assertTrue(pair.isPresent(), "name pairs built from video faces must be proposed");
        assertEquals(Set.of(alice, bob), Set.of(pair.get().nameIdA(), pair.get().nameIdB()));
        assertEquals(1.0, pair.get().similarity(), 1e-6);
    }

    @Test
    void markNotDupes_excludesPairFromThisAndLaterRuns() throws SQLException {
        long alice = addName("Alice");
        long bob = addName("Bob");
        addFace("imgA1", alice, identical());
        addFace("imgB1", bob, identical());

        service.markNotDupes(alice, bob);

        assertTrue(service.nextPair().isEmpty());
        assertTrue(notDupeDao.isNotDupe(alice, bob));
        assertTrue(notDupeDao.isNotDupe(bob, alice));
    }

    @Test
    void skip_skipsPairThisRun_onlyUntilReset() throws SQLException {
        long alice = addName("Alice");
        long bob = addName("Bob");
        addFace("imgA1", alice, identical());
        addFace("imgB1", bob, identical());

        service.skip(alice, bob);
        assertTrue(service.nextPair().isEmpty());

        service.reset();
        Optional<DeduplicationService.DupeCandidate> afterReset = service.nextPair();
        assertTrue(afterReset.isPresent());
        assertEquals(Set.of(alice, bob), Set.of(afterReset.get().nameIdA(), afterReset.get().nameIdB()));
    }

    @Test
    void merge_reassignsFacesAndDeletesEliminatedName() throws SQLException {
        long carol = addName("Carol");
        long dave = addName("Dave");
        addFace("imgC1", carol, identical());
        addFace("imgC2", carol, identical());
        addFace("imgD1", dave, identical());
        addFace("imgD2", dave, identical());
        notDupeDao.insert(carol, dave);

        service.merge(carol, dave);

        assertEquals(4, faceDao.countByNameId(carol));
        assertEquals(0, faceDao.countByNameId(dave));
        assertTrue(nameDao.findById(dave).isEmpty());
        assertTrue(notDupeDao.findAll().isEmpty(), "not_dupes involving Dave must cascade away");
    }
}