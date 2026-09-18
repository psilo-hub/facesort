package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.model.SimilarityResult;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Implements the "Put a face to a name" flow (IMPLEMENTATION_PLAN.md section 6.5).
 *
 * <p>Given a name selected by the user, the service ranks all unnamed faces by
 * similarity to the average embedding of the faces already tagged with that
 * name, so the user can batch-tag the most similar ones without searching.</p>
 */
public class FaceToNameService {

    private final FaceAiService faceAiService;
    private final FaceDao faceDao;
    private final NameDao nameDao;
    private final ImageDao imageDao;
    private final ConfigModel config;

    /**
     * Creates the service.
     *
     * @param faceAiService engine for embedding math; must not be null
     * @param faceDao       data access for faces; must not be null
     * @param nameDao       data access for names; must not be null
     * @param imageDao      data access for images and image_paths; must not be null
     * @param config        application settings; must not be null
     */
    public FaceToNameService(FaceAiService faceAiService, FaceDao faceDao, NameDao nameDao,
                             ImageDao imageDao, ConfigModel config) {
        this.faceAiService = Objects.requireNonNull(faceAiService, "faceAiService");
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
        this.nameDao = Objects.requireNonNull(nameDao, "nameDao");
        this.imageDao = Objects.requireNonNull(imageDao, "imageDao");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Returns all names, ordered alphabetically, for the dropdown list.
     *
     * @return all names in the database
     * @throws SQLException on database error
     */
    public List<NameRecord> getAllNames() throws SQLException {
        return nameDao.findAll();
    }

    /**
     * Returns the top {@code limit} unnamed faces most similar to the average
     * embedding of the given name, sorted by similarity descending.
     *
     * <p>Equivalent to {@link #findUnnamedForName(long, int, boolean)} with
     * {@code excludeCloserToOtherNames} set to {@code false}.</p>
     *
     * @param nameId id of the reference name
     * @param limit  maximum number of results; values &le; 0 yield an empty list
     * @return matching faces as {@link SimilarityResult}, descending by similarity
     * @throws SQLException on database error
     */
    public List<SimilarityResult> findUnnamedForName(long nameId, int limit) throws SQLException {
        return findUnnamedForName(nameId, limit, false);
    }

    /**
     * Returns the top {@code limit} unnamed faces most similar to the average
     * embedding of the given name, sorted by similarity descending.
     *
     * <p>Equivalent to {@link #findUnnamedForName(long, int, boolean, String)}
     * with both {@code excludeCloserToOtherNames} and {@code pathPrefix} left
     * at their disabled defaults.</p>
     *
     * @param nameId                     id of the reference name
     * @param limit                      maximum number of results; values &le; 0
     *                                   yield an empty list
     * @param excludeCloserToOtherNames  whether to hide faces that are closer to
     *                                   another name's average embedding than to
     *                                   the selected name's
     * @return matching faces as {@link SimilarityResult}, descending by similarity
     * @throws SQLException on database error
     */
    public List<SimilarityResult> findUnnamedForName(long nameId, int limit,
                                                     boolean excludeCloserToOtherNames) throws SQLException {
        return findUnnamedForName(nameId, limit, excludeCloserToOtherNames, null);
    }

    /**
     * Returns the top {@code limit} unnamed faces most similar to the average
     * embedding of the given name, sorted by similarity descending.
     *
     * <p>The average embedding is computed over all faces currently tagged with
     * {@code nameId}. If the name has no faces (or none with embeddings), the
     * result is empty and no average is computed. Candidates whose similarity
     * falls below {@link ConfigModel#getMinNameSimilarity()} never qualify, so
     * faces that are not similar enough cannot be added to an existing name.</p>
     *
     * <p>When {@code pathPrefix} is non-blank, only candidates whose images
     * have at least one stored path starting with that prefix qualify, so the
     * user can restrict the offered faces to a folder or file.</p>
     *
     * <p>When {@code excludeCloserToOtherNames} is {@code true}, each candidate
     * is additionally compared against the average embedding of every other
     * name; a candidate is dropped whenever it is strictly more similar to
     * another name's average than to {@code nameId}'s average, so only faces
     * whose best match is the selected name are offered.</p>
     *
     * @param nameId                     id of the reference name
     * @param limit                      maximum number of results; values &le; 0
     *                                   yield an empty list
     * @param excludeCloserToOtherNames  whether to hide faces that are closer to
     *                                   another name's average embedding than to
     *                                   the selected name's
     * @param pathPrefix                 path prefix the stored image path must
     *                                   start with, or {@code null}/{@code ""}
     *                                   for any faces
     * @return matching faces as {@link SimilarityResult}, descending by similarity
     * @throws SQLException on database error
     */
    public List<SimilarityResult> findUnnamedForName(long nameId, int limit,
                                                     boolean excludeCloserToOtherNames,
                                                     String pathPrefix) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }

        List<FaceRecord> namedFaces = faceDao.findByNameId(nameId);
        if (namedFaces.isEmpty()) {
            return List.of();
        }

        float[] average = averageOf(namedFaces);
        double cutoff = config.getMinNameSimilarity();

        Map<Long, float[]> otherAverages = excludeCloserToOtherNames
                ? averageOfOtherNames(nameId) : Map.of();

        List<SimilarityResult> results = new ArrayList<>();
        for (FaceRecord candidate : faceDao.findUnnamed(pathPrefix)) {
            double similarity = faceAiService.calcSimilarity(average, candidate.embedding());
            if (similarity < cutoff) {
                continue;
            }
            if (excludeCloserToOtherNames
                    && isCloserToAnotherName(candidate, similarity, otherAverages)) {
                continue;
            }
            results.add(new SimilarityResult(candidate, similarity));
        }

        // SimilarityResult orders descending by similarity.
        Collections.sort(results);

        return results.size() <= limit ? results : new ArrayList<>(results.subList(0, limit));
    }

    /**
     * Returns the average embedding of all faces tagged with the given name,
     * or an empty map when no other name has any tagged faces.
     *
     * @param nameId the name to skip (the currently selected name)
     * @return a mapping of every other name id to its average embedding
     * @throws SQLException on database error
     */
    private Map<Long, float[]> averageOfOtherNames(long nameId) throws SQLException {
        Map<Long, float[]> averages = new HashMap<>();
        for (NameRecord other : nameDao.findAll()) {
            if (other.id() == nameId) {
                continue;
            }
            List<FaceRecord> faces = faceDao.findByNameId(other.id());
            if (faces.isEmpty()) {
                continue;
            }
            averages.put(other.id(), averageOf(faces));
        }
        return averages;
    }

    /**
     * Tells whether the given candidate is strictly more similar to any other
     * name's average embedding than to the selected name's.
     *
     * @param candidate              the unnamed face being checked
     * @param similarityToSelected   the candidate's similarity to the selected name
     * @param otherAverages          other names' average embeddings
     * @return {@code true} if some other name matches the candidate more closely
     */
    private boolean isCloserToAnotherName(FaceRecord candidate, double similarityToSelected,
                                          Map<Long, float[]> otherAverages) {
        for (float[] otherAverage : otherAverages.values()) {
            if (faceAiService.calcSimilarity(otherAverage, candidate.embedding())
                    > similarityToSelected) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes the component-wise average embedding of the given faces.
     *
     * @param faces the faces to average; must not be empty
     * @return the average embedding
     */
    private float[] averageOf(List<FaceRecord> faces) {
        List<float[]> embeddings = new ArrayList<>(faces.size());
        for (FaceRecord face : faces) {
            embeddings.add(face.embedding());
        }
        return faceAiService.calcAverage(embeddings);
    }

    /**
     * Returns the top {@code limit} faces already tagged with the given name
     * that are most similar to the name's average embedding, sorted by
     * descending similarity.
     *
     * <p>The average embedding is computed over all faces currently tagged with
     * {@code nameId}; each of those faces is then scored against that average.
     * If the name has no faces, the result is empty.</p>
     *
     * @param nameId id of the name whose tagged faces should be ranked
     * @param limit  maximum number of results; values &le; 0 yield an empty list
     * @return the name's tagged faces as {@link SimilarityResult}, descending by similarity
     * @throws SQLException on database error
     */
    public List<SimilarityResult> findMostSimilarNamed(long nameId, int limit) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }

        List<FaceRecord> namedFaces = faceDao.findByNameId(nameId);
        if (namedFaces.isEmpty()) {
            return List.of();
        }

        float[] average = averageOf(namedFaces);

        List<SimilarityResult> results = new ArrayList<>();
        for (FaceRecord face : namedFaces) {
            results.add(new SimilarityResult(face,
                    faceAiService.calcSimilarity(average, face.embedding())));
        }

        // SimilarityResult orders descending by similarity.
        Collections.sort(results);

        return results.size() <= limit ? results : new ArrayList<>(results.subList(0, limit));
    }

    /**
     * Tags multiple faces with the given name.
     *
     * @param faceIds ids of the faces to tag; must not be null
     * @param nameId  id of the name to assign
     * @throws SQLException on database error
     */
    public void tagFaces(List<Long> faceIds, long nameId) throws SQLException {
        Objects.requireNonNull(faceIds, "faceIds");
        for (Long faceId : faceIds) {
            faceDao.assignName(faceId, nameId);
        }
    }

    /**
     * Renames a name, preserving its id, tagged faces and {not_dupes} links.
     *
     * <p>The new name is trimmed; blank names and names that already belong to a
     * <em>different</em> name row are rejected, because {@code names.name} is
     * unique. Renaming a name to itself (possibly with different surrounding
     * whitespace) is a harmless no-op.</p>
     *
     * @param nameId  id of the name to rename
     * @param newName the new display name; must not be blank
     * @return the updated name record with its current face count
     * @throws NullPointerException     if {@code newName} is null
     * @throws IllegalArgumentException if {@code newName} is blank or already
     *                                  used by another name
     * @throws SQLException             on database access failure
     */
    public NameRecord renameName(long nameId, String newName) throws SQLException {
        String trimmed = Objects.requireNonNull(newName, "newName").trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        NameRecord current = nameDao.findById(nameId)
                .orElseThrow(() -> new IllegalArgumentException("Name not found: " + nameId));
        if (trimmed.equals(current.name())) {
            return current;
        }
        Optional<NameRecord> clash = nameDao.findByName(trimmed);
        if (clash.isPresent() && clash.get().id() != nameId) {
            throw new IllegalArgumentException("A name called '" + trimmed + "' already exists.");
        }
        nameDao.rename(nameId, trimmed);
        return nameDao.findById(nameId)
                .orElseThrow(() -> new SQLException("Could not reload renamed name"));
    }

    /**
     * Tells whether the original file for the given image hash still exists on
     * disk.
     *
     * @param imageHash hash of the source image; must not be null
     * @return {@code true} if at least one stored path exists on disk
     * @throws NullPointerException if {@code imageHash} is null
     * @throws SQLException         if the database operation fails
     */
    public boolean isOriginalAvailable(String imageHash) throws SQLException {
        for (String path : imageDao.getPaths(Objects.requireNonNull(imageHash, "imageHash"))) {
            if (java.nio.file.Files.exists(java.nio.file.Path.of(path))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens the original file of the source image for a face in the operating
     * system default viewer.
     *
     * <p>The first still-existing stored path for the image is used. Returns
     * {@code false} without side effects when no stored path exists on disk or
     * when the desktop platform does not support opening files.</p>
     *
     * @param imageHash hash of the source image; must not be null
     * @return {@code true} if the file was handed to the default viewer
     * @throws IOException  if the default viewer cannot open the file
     * @throws SQLException on database access failure
     */
    public boolean openOriginal(String imageHash) throws IOException, SQLException {
        List<String> paths = imageDao.getPaths(Objects.requireNonNull(imageHash, "imageHash"));
        Optional<Path> existing = ViewService.firstExistingPath(paths);
        if (existing.isEmpty()) {
            return false;
        }
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            return false;
        }
        Desktop.getDesktop().open(existing.get().toFile());
        return true;
    }
}