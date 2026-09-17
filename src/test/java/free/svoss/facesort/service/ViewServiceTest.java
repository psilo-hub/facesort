package free.svoss.facesort.service;

import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private ViewService service;
    private final Set<String> insertedImages = new HashSet<>();
    private final AtomicInteger bbox = new AtomicInteger();

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.inMemory();
        faceDao = new FaceDao(db.getConnection());
        nameDao = new NameDao(db.getConnection());
        imageDao = new ImageDao(db.getConnection());
        service = new ViewService(new FaceAiService(new FakeFaceAiEngine()),
                faceDao, nameDao, imageDao);
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    private long addFace(String imageHash, float[] embedding, Long nameId) throws SQLException {
        if (insertedImages.add(imageHash)) {
            imageDao.insert(imageHash, 0, "{}", 1);
        }
        int offset = bbox.getAndAdd(20);
        return faceDao.insert(new FaceRecord(
                0, imageHash, offset, 0, 80, 80, 0.9, embedding, new byte[]{1}, nameId));
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
}