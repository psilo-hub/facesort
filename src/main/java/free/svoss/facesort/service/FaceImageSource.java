package free.svoss.facesort.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Supplies the original pixels a stored image was derived from.
 *
 * <p>A stored image is either a photo file or a frame sampled out of a video,
 * so re-deriving a face crop after the fact needs a component that knows how to
 * get back to that original. Mirrors the {@code FaceAiService.Engine} seam: the
 * face-rotation migration consumes this interface so it can be exercised with an
 * in-memory double, while the production implementation resolves the stored
 * photo and video paths.</p>
 */
interface FaceImageSource extends AutoCloseable {

    /**
     * Loads the original image behind a stored hash.
     *
     * @param imageHash the stored image (or video frame) hash
     * @return the original image, or {@code null} when no readable source
     *         remains on disk
     * @throws IOException  when a source exists but cannot be read
     * @throws SQLException when the stored paths cannot be queried
     */
    BufferedImage load(String imageHash) throws IOException, SQLException;

    @Override
    void close();
}
