package free.svoss.facesort.service;

import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

/**
 * Narrow service interface for the "View" tab.
 *
 * <p>Only exposes the operations the tab needs: listing name summaries,
 * loading images for a name, and untagging faces from an image. Plus the
 * media-file actions used by the face context menus. Kept intentionally small
 * so the view can be tested with a minimal mock.</p>
 */
public interface ViewService extends MediaFileActions {

    /**
     * Summary card for one name in the view grid.
     *
     * @param name           the name
     * @param faceCount      number of faces tagged with the name
     * @param representative the face closest to the name's average embedding
     */
    record NameSummary(NameRecord name, int faceCount, FaceRecord representative) {
    }

    /**
     * A single image containing at least one face with a given name.
     *
     * @param hash         content hash of the image
     * @param thumbnailJpg stored JPEG thumbnail, or {@code null} when absent
     */
    record NamedImage(String hash, byte[] thumbnailJpg) {
    }

    /**
     * Returns a summary card for every name that has at least one tagged face.
     *
     * @return summaries in name order, without names that have no faces
     * @throws SQLException on database access failure
     */
    List<NameSummary> getNameSummaries() throws SQLException;

    /**
     * Returns the distinct images that contain at least one face with the given
     * name, together with their stored thumbnails.
     *
     * @param nameId id of the name
     * @return distinct images in the order of each image's first tagged face
     * @throws SQLException on database access failure
     */
    List<NamedImage> getImagesForName(long nameId) throws SQLException;

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
    int untagFacesFromImage(String imageHash, long nameId) throws SQLException;
}
