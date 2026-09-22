package free.svoss.facesort.service;

import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.model.SimilarityResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
    private final VideoDao videoDao;
    private final ConfigModel config;

    /**
     * Creates the service.
     *
     * @param faceAiService engine for embedding math; must not be null
     * @param faceDao       data access for faces; must not be null
     * @param nameDao       data access for names; must not be null
     * @param imageDao      data access for images and image_paths; must not be null
     * @param videoDao      data access for videos, video_paths and video_frames; must not be null
     * @param config        application settings; must not be null
     */
    public FaceToNameService(FaceAiService faceAiService, FaceDao faceDao, NameDao nameDao,
                             ImageDao imageDao, VideoDao videoDao, ConfigModel config) {
        this.faceAiService = Objects.requireNonNull(faceAiService, "faceAiService");
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
        this.nameDao = Objects.requireNonNull(nameDao, "nameDao");
        this.imageDao = Objects.requireNonNull(imageDao, "imageDao");
        this.videoDao = Objects.requireNonNull(videoDao, "videoDao");
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
     * have at least one stored photo path starting with that prefix qualify,
     * or whose linked video file path starts with it (video frames), so the
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
     * @param pathPrefix                 path prefix the stored photo/video path
     *                                   must start with, or {@code null}/{@code ""}
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
     * Picks the face whose embedding is most similar to the given average
     * embedding. The caller guarantees at least one face.
     *
     * @param faces   the faces to choose from; must not be empty
     * @param average the average embedding to compare against
     * @return the closest face
     */
    private FaceRecord mostSimilarToAverage(List<FaceRecord> faces, float[] average) {
        FaceRecord best = faces.get(0);
        double bestScore = -1.0;
        for (FaceRecord face : faces) {
            double score = faceAiService.calcSimilarity(average, face.embedding());
            if (score > bestScore) {
                bestScore = score;
                best = face;
            }
        }
        return best;
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
     * Assigns the given name to the given face.
     *
     * @param faceId id of the face to tag
     * @param nameId id of the name to assign
     * @throws SQLException if the update fails
     */
    public void tagFace(long faceId, long nameId) throws SQLException {
        faceDao.assignName(faceId, nameId);
    }

    /**
     * Returns the existing name record for the given display name, or empty
     * when the name has not been created yet. The lookup is trimmed.
     *
     * @param name the display name; must not be null or blank
     * @return the existing record, or empty if the name is new
     * @throws NullPointerException if {@code name} is null
     * @throws SQLException         if the database operation fails
     */
    public Optional<NameRecord> findName(String name) throws SQLException {
        return nameDao.findByName(Objects.requireNonNull(name, "name").trim());
    }

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
    public long createOrFindName(String name) throws SQLException {
        String trimmed = Objects.requireNonNull(name, "name").trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Optional<NameRecord> existing = nameDao.findByName(trimmed);
        if (existing.isPresent()) {
            return existing.get().id();
        }
        return nameDao.insert(trimmed);
    }

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
    public Optional<NamePreview> previewTagWithName(String name, long candidateFaceId) throws SQLException {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        Optional<NameRecord> existing = nameDao.findByName(trimmed);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        List<FaceRecord> namedFaces = faceDao.findByNameId(existing.get().id());
        if (namedFaces.isEmpty()) {
            return Optional.empty();
        }
        float[] average = averageOf(namedFaces);
        FaceRecord representative = mostSimilarToAverage(namedFaces, average);
        FaceRecord candidate = faceDao.findById(candidateFaceId)
                .orElseThrow(() -> new IllegalArgumentException("Face not found: " + candidateFaceId));
        return Optional.of(new NamePreview(representative,
                faceAiService.calcSimilarity(average, candidate.embedding())));
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
        return ViewService.resolveOriginalFile(imageDao, videoDao, imageHash).isPresent();
    }

    /**
     * Opens the original file of the source image for a face in the operating
     * system default viewer. For video frames this is the source video the
     * frame came from.
     *
     * <p>Returns {@code false} without side effects when no path exists on disk
     * or when the desktop platform does not support opening files.</p>
     *
     * @param imageHash hash of the source image; must not be null
     * @return {@code true} if the file was handed to the default viewer
     * @throws IOException  if the default viewer cannot open the file
     * @throws SQLException on database access failure
     */
    public boolean openOriginal(String imageHash) throws IOException, SQLException {
        return ViewService.openInDefaultViewer(imageDao, videoDao, imageHash);
    }

    /**
     * Opens the folder that contains the source image for a face in the
     * operating system file manager. For video frames this is the folder of the
     * source video the frame came from.
     *
     * <p>Returns {@code false} without side effects when no original file
     * exists on disk or when the desktop platform does not support opening
     * folders.</p>
     *
     * @param imageHash hash of the source image; must not be null
     * @return {@code true} if the folder was handed to the file manager
     * @throws IOException  if the file manager cannot open the folder
     * @throws SQLException on database access failure
     */
    public boolean openContainingFolder(String imageHash) throws IOException, SQLException {
        return ViewService.openContainingFolder(imageDao, videoDao, imageHash);
    }

    /**
     * Tells whether the folder containing the original file for the given image
     * hash exists on disk and can be opened.
     *
     * @param imageHash hash of the source image; must not be null
     * @return {@code true} if a containing folder can be opened
     * @throws NullPointerException if {@code imageHash} is null
     * @throws SQLException         if the database operation fails
     */
    public boolean isContainingFolderAvailable(String imageHash) throws SQLException {
        return ViewService.resolveContainingFolder(imageDao, videoDao, imageHash).isPresent();
    }

    /**
     * Resolves the folder of the media file behind a face for use as a path
     * filter prefix. For a video frame this is the folder of its source video,
     * otherwise the folder of the image itself; when several paths are stored
     * the first one that still exists on disk is preferred.
     *
     * @param imageHash hash of the source image; must not be null
     * @return the folder to filter by, or empty when no path is stored at all
     * @throws NullPointerException if {@code imageHash} is null
     * @throws SQLException         if the database operation fails
     */
    public Optional<Path> resolveFilterFolder(String imageHash) throws SQLException {
        return ViewService.resolveFilterFolder(imageDao, videoDao, imageHash);
    }

    /**
     * Exports every distinct image that contains the given person to the output
     * folder.
     *
     * <p>Images whose original file still exists on disk are copied with their
     * original file name. When the original is unavailable, the stored JPEG
     * thumbnail is written instead, named after the first stored path's base
     * name (with a {@code .jpg} extension) or after the content hash when no
     * path is recorded at all. Images with neither a reachable original nor a
     * thumbnail are counted as missing.</p>
     *
     * <p>Each image is exported at most once, even when it contains several
     * faces of the person. File-name collisions in the output folder are
     * resolved by appending {@code  (1)}, {@code  (2)}, and so on. When an
     * original already lives in the output folder (the export target equals a
     * source folder), it counts as copied without being copied onto itself.</p>
     *
     * @param nameId    id of the person whose images should be exported
     * @param outputDir the folder to copy the images into; created recursively
     *                  when it does not exist
     * @return the export summary
     * @throws NullPointerException if {@code outputDir} is null
     * @throws SQLException         when the database access fails
     * @throws IOException          when a file cannot be copied or written
     */
    public ExportResult exportImagesForName(long nameId, Path outputDir)
            throws SQLException, IOException {
        Files.createDirectories(Objects.requireNonNull(outputDir, "outputDir"));

        List<FaceRecord> faces = faceDao.findByNameId(nameId);
        Set<String> hashes = new LinkedHashSet<>();
        for (FaceRecord face : faces) {
            hashes.add(face.imageHash());
        }

        int originalsCopied = 0;
        int thumbnailsCopied = 0;
        int missing = 0;
        Set<String> usedNames = new HashSet<>();
        for (String hash : hashes) {
            Optional<Path> existing = ViewService.firstExistingPath(imageDao.getPaths(hash));
            if (existing.isPresent()) {
                Path destination = uniqueDestination(
                        outputDir, existing.get().getFileName().toString(), usedNames);
                if (!destination.toAbsolutePath().normalize()
                        .equals(existing.get().toAbsolutePath().normalize())) {
                    Files.copy(existing.get(), destination);
                }
                originalsCopied++;
                continue;
            }

            byte[] thumbnail = imageDao.getThumbnail(hash);
            if (thumbnail == null || thumbnail.length == 0) {
                missing++;
                continue;
            }
            Path destination = uniqueDestination(
                    outputDir, fallbackThumbnailName(imageDao.getPaths(hash), hash), usedNames);
            Files.write(destination, thumbnail);
            thumbnailsCopied++;
        }

        return new ExportResult(hashes.size(), originalsCopied, thumbnailsCopied, missing);
    }

    /**
     * Returns a destination path in {@code outputDir} whose file name is not
     * yet in {@code usedNames}. Collisions get a numeric suffix inserted before
     * the extension.
     *
     * @param outputDir the export folder
     * @param fileName  the desired file name
     * @param usedNames the names already used in this export run
     * @return a collision-free destination path
     */
    private static Path uniqueDestination(Path outputDir, String fileName, Set<String> usedNames) {
        String candidate = fileName;
        int suffix = 1;
        while (usedNames.contains(candidate)) {
            candidate = withNumericSuffix(fileName, suffix);
            suffix++;
        }
        usedNames.add(candidate);
        return outputDir.resolve(candidate);
    }

    /**
     * Inserts {@code  (n)} before the last extension of a file name.
     *
     * @param name the original file name
     * @param n    the collision counter
     * @return the suffixed name
     */
    private static String withNumericSuffix(String name, int n) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name + " (" + n + ")";
        }
        return name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
    }

    /**
     * Derives the name for a thumbnail-exported image: the base name of the
     * first stored path with a {@code .jpg} extension, or {@code hash + ".jpg"}
     * when no path is stored.
     *
     * @param storedPaths stored paths for the image, in priority order
     * @param hash        content hash of the image
     * @return a stable base name for the exported JPEG thumbnail
     */
    private static String fallbackThumbnailName(List<String> storedPaths, String hash) {
        for (String path : storedPaths) {
            String base = Path.of(path).getFileName().toString();
            if (!base.isBlank()) {
                int dot = base.lastIndexOf('.');
                return dot > 0 ? base.substring(0, dot) + ".jpg" : base + ".jpg";
            }
        }
        return hash + ".jpg";
    }

    /**
     * Summary of an export run.
     *
     * @param images           distinct images that contain the person
     * @param originalsCopied  images whose original file was copied
     * @param thumbnailsCopied images exported as a stored thumbnail instead
     * @param missing          images that could not be exported at all
     */
    public record ExportResult(int images, int originalsCopied,
                               int thumbnailsCopied, int missing) {
    }

    /**
     * Preview of what tagging a face with an already-existing name entails.
     *
     * @param representative the face tagged with the name that is closest to
     *                       its average embedding
     * @param similarity     similarity between the candidate face and the
     *                       name's average embedding, in [0, 1]
     */
    public record NamePreview(FaceRecord representative, double similarity) {
    }
}
