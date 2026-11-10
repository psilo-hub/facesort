package free.svoss.facesort.service;

import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.model.FaceRecord;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Caches each name's average face embedding so the "Most similar" and "Add
 * faces to a name" flows do not recompute it for every decision.
 *
 * <p>The average only changes when a face is tagged, untagged, reassigned or
 * its embedding is updated. Every such write goes through {@link FaceDao},
 * which exposes a monotonically increasing {@link FaceDao#writeVersion() write
 * version}; the cache drops every entry as soon as it sees a newer version, so
 * writes from any tab or background pass invalidate it without each service
 * having to know about the cache. Decisions that only record a rejection or a
 * session skip do not touch the faces table and therefore keep the cache warm.</p>
 *
 * <p>Averages are persisted to the database via {@link NameDao} so the first
 * scan after startup does not have to rebuild them all. Stored rows are
 * validated against the current face count on load; a mismatch means the
 * average is recomputed from the tagged faces and re-stored.</p>
 *
 * <p>Instances are safe to use from the background tasks of several tabs.</p>
 */
final class NameAverageCache {

    private final FaceDao faceDao;
    private final NameDao nameDao;
    private final FaceSelector faceSelector;
    private final Map<Long, float[]> averages = new HashMap<>();

    /** The face-write version the cached entries belong to. */
    private long version = -1;

    /** Whether this is the first {@link #syncWithWrites()} call; preserves DB rows on restart. */
    private boolean firstLoad = true;

    /**
     * Creates a cache backed by the given face data.
     *
     * @param faceDao      data access for faces; must not be null
     * @param nameDao      data access for names and stored averages; must not be null
     * @param faceSelector embedding math; must not be null
     */
    NameAverageCache(FaceDao faceDao, NameDao nameDao, FaceSelector faceSelector) {
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
        this.nameDao = Objects.requireNonNull(nameDao, "nameDao");
        this.faceSelector = Objects.requireNonNull(faceSelector, "faceSelector");
    }

    /**
     * Returns the average embedding of all faces tagged with the given name.
     *
     * @param nameId id of the name
     * @return the cached or newly computed average, or empty when the name has
     *         no faces
     * @throws SQLException on database error
     */
    synchronized Optional<float[]> averageFor(long nameId) throws SQLException {
        return Optional.ofNullable(averagesFor(List.of(nameId)).get(nameId));
    }

    /**
     * Returns the average embeddings of the given names, computing only those
     * that are not cached yet. Stored database averages are reused when their
     * face count still matches; otherwise they are recomputed from the tagged
     * faces and re-stored.
     *
     * @param nameIds the names to look up; must not be null
     * @return the average embedding of every requested name that has faces
     * @throws SQLException on database error
     */
    synchronized Map<Long, float[]> averagesFor(List<Long> nameIds) throws SQLException {
        syncWithWrites();

        Set<Long> missing = new LinkedHashSet<>();
        for (Long nameId : nameIds) {
            if (!averages.containsKey(nameId)) {
                missing.add(nameId);
            }
        }
        if (!missing.isEmpty()) {
            loadAndComputeMissing(missing);
        }

        Map<Long, float[]> result = new LinkedHashMap<>();
        for (Long nameId : nameIds) {
            float[] average = averages.get(nameId);
            if (average != null) {
                result.put(nameId, average);
            }
        }
        return result;
    }

    /**
     * For each missing name, tries the stored database average first (validated
     * against the current face count), then falls back to computing from the
     * tagged faces and storing the result. All database reads are batched:
     * stored averages and face counts are loaded once for all missing names,
     * and only names whose stored average is stale get recomputed.
     *
     * @param missing name ids that are not in the memory cache
     * @throws SQLException on database error
     */
    private void loadAndComputeMissing(Set<Long> missing) throws SQLException {
        List<Long> missingList = new ArrayList<>(missing);

        // Batch-load stored averages (queries name_averages, not faces).
        Map<Long, NameDao.NameAverage> stored = nameDao.loadNameAverages(missingList);

        // Batch-load faces for all missing names (one chunked faces query).
        Map<Long, List<FaceRecord>> loaded = faceDao.findSlimByNameIds(missingList);

        // Determine which names need recomputation, counting faces from the
        // loaded rows instead of issuing a separate COUNT query per name.
        List<Long> toCompute = new ArrayList<>();
        Map<Long, NameDao.NameAverage> toStore = new LinkedHashMap<>();
        for (Long nameId : missingList) {
            NameDao.NameAverage avg = stored.get(nameId);
            List<FaceRecord> faces = loaded.getOrDefault(nameId, List.of());
            int faceCount = faces.size();
            if (avg != null && avg.faceCount() == faceCount && !faces.isEmpty()) {
                averages.put(nameId, avg.embedding());
            } else if (!faces.isEmpty()) {
                float[] average = faceSelector.averageOf(faces);
                averages.put(nameId, average);
                toStore.put(nameId, new NameDao.NameAverage(average, faceCount));
            }
        }

        // Store all computed averages in one go.
        if (!toStore.isEmpty()) {
            nameDao.storeNameAverages(toStore);
        }
    }

    /** Drops every entry when a face write happened since the last lookup. */
    private void syncWithWrites() throws SQLException {
        long current = faceDao.writeVersion();
        if (current != version) {
            averages.clear();
            if (!firstLoad) {
                nameDao.deleteAllNameAverages();
            }
            firstLoad = false;
            version = current;
        }
    }
}
