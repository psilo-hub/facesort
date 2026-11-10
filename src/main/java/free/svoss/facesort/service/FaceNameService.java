package free.svoss.facesort.service;

import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.model.SimilarityResult;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Narrow service interface for the "Add faces to a name" tab.
 *
 * <p>Only exposes the operations the tab needs: loading names, ranking unnamed
 * faces by similarity to a name's average embedding, tagging faces, renaming
 * names, exporting images for a name, marking faces as not-a-face, and
 * resolving filter paths. Plus the media-file actions used by the face context
 * menus. Kept intentionally small so the view can be tested with a minimal
 * mock.</p>
 */
public interface FaceNameService extends MediaFileActions {

    /**
     * Preview of what tagging a face with an already-existing name entails.
     *
     * @param representative the face tagged with the name that is closest to
     *                       its average embedding
     * @param similarity     similarity between the candidate face and the
     *                       name's average embedding, in [0, 1]
     */
    record NamePreview(FaceRecord representative, double similarity) {
    }

    /**
     * Summary of an export run.
     *
     * @param images           distinct images that contain the person
     * @param originalsCopied  images whose original file was copied
     * @param thumbnailsCopied images exported as a stored thumbnail instead
     * @param missing          images that could not be exported at all
     */
    record ExportResult(int images, int originalsCopied,
                        int thumbnailsCopied, int missing) {
    }

    /**
     * Returns all names, ordered alphabetically, for the dropdown list.
     *
     * @return all names in the database
     * @throws SQLException on database error
     */
    List<NameRecord> getAllNames() throws SQLException;

    /**
     * Returns the top {@code limit} faces already tagged with the given name
     * that are most similar to the name's average embedding, sorted by
     * descending similarity.
     *
     * @param nameId id of the name whose tagged faces should be ranked
     * @param limit  maximum number of results; values &le; 0 yield an empty list
     * @return the name's tagged faces as {@link SimilarityResult}, descending by similarity
     * @throws SQLException on database error
     */
    List<SimilarityResult> findMostSimilarNamed(long nameId, int limit) throws SQLException;

    /**
     * Returns the top {@code limit} unnamed faces most similar to the average
     * embedding of the given name, sorted by similarity descending.
     *
     * @param nameId                     id of the reference name
     * @param limit                      maximum number of results; values &le; 0
     *                                   yield an empty list
     * @param excludeCloserToOtherNames  whether to hide faces that are closer to
     *                                   another name's average embedding than to
     *                                   the selected name's
     * @param pathPrefix                 path prefix the stored photo/video path
     *                                   must start with, or {@code null}/{@code ""}
     *                                   for any faces
     * @return matching faces as {@link SimilarityResult}, descending by similarity
     * @throws SQLException on database error
     */
    List<SimilarityResult> findUnnamedForName(long nameId, int limit,
                                              boolean excludeCloserToOtherNames,
                                              String pathPrefix) throws SQLException;

    /**
     * Records that the given face is not a face after all, taking it out of the
     * candidate suggestions of every name. The face row is kept, so the decision
     * survives a re-import of the image.
     *
     * @param faceId id of the face to dismiss
     * @throws SQLException if the update fails
     */
    void markNotAFace(long faceId) throws SQLException;

    /**
     * Computes a preview for tagging the given face with the given name: when
     * the name already exists, the face tagged with it that is closest to its
     * average embedding and the similarity between the given face's embedding
     * and that average. Empty when the name is blank or new, or when the
     * existing name has no faces yet.
     *
     * @param name            the typed display name; may be null
     * @param candidateFaceId the id of the face the user wants to tag
     * @return the preview for an existing name, or empty otherwise
     * @throws IllegalArgumentException if {@code candidateFaceId} is unknown
     * @throws SQLException             if the database operation fails
     */
    Optional<NamePreview> previewTagWithName(String name, long candidateFaceId)
            throws SQLException;

    /**
     * Returns the id of the name, creating it if it does not exist yet.
     *
     * <p>The input is trimmed before lookup so accidental leading/trailing
     * whitespace does not create duplicate names.</p>
     *
     * @param name the display name; must not be null or blank
     * @return the existing or newly created name id
     * @throws NullPointerException     if {@code name} is null
     * @throws IllegalArgumentException if {@code name} is blank
     * @throws SQLException             if the database operation fails
     */
    long createOrFindName(String name) throws SQLException;

    /**
     * Tags multiple faces with the given name.
     *
     * @param faceIds ids of the faces to tag; must not be null
     * @param nameId  id of the name to assign
     * @throws SQLException on database error
     */
    void tagFaces(List<Long> faceIds, long nameId) throws SQLException;

    /**
     * Renames a name, preserving its id, tagged faces and {not_dupes} links.
     *
     * @param nameId  id of the name to rename
     * @param newName the new display name; must not be blank
     * @return the updated name record with its current face count
     * @throws NullPointerException     if {@code newName} is null
     * @throws IllegalArgumentException if {@code newName} is blank or already
     *                                  used by a different name
     * @throws SQLException             on database access failure
     */
    NameRecord renameName(long nameId, String newName) throws SQLException;

    /**
     * Exports every distinct image that contains the given person to the output
     * folder.
     *
     * @param nameId    id of the person whose images should be exported
     * @param outputDir the folder to copy the images into; created recursively
     *                  when it does not exist
     * @return the export summary
     * @throws SQLException         when the database access fails
     * @throws IOException          when a file cannot be copied or written
     */
    ExportResult exportImagesForName(long nameId, Path outputDir)
            throws SQLException, IOException;

    /**
     * Resolves the path to filter by for the media file behind a face. For a
     * video frame this is the full path of its source video (including the file
     * name), otherwise the folder of the image itself; when several paths are
     * stored the first one that still exists on disk is preferred.
     *
     * @param imageHash hash of the source image; must not be null
     * @return the path to filter by, or empty when no path is stored at all
     * @throws NullPointerException if {@code imageHash} is null
     * @throws SQLException         if the database operation fails
     */
    Optional<Path> resolveFilterPath(String imageHash) throws SQLException;
}
