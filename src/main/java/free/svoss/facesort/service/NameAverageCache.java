package free.svoss.facesort.service;

import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.model.FaceRecord;

import java.sql.SQLException;
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
 * <p>Instances are safe to use from the background tasks of several tabs.</p>
 */
final class NameAverageCache {

    private final FaceDao faceDao;
    private final FaceSelector faceSelector;
    private final Map<Long, float[]> averages = new HashMap<>();

    /** The face-write version the cached entries belong to. */
    private long version = -1;

    /**
     * Creates a cache backed by the given face data.
     *
     * @param faceDao      data access for faces; must not be null
     * @param faceSelector embedding math; must not be null
     */
    NameAverageCache(FaceDao faceDao, FaceSelector faceSelector) {
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
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
     * that are not cached yet in one grouped slim query.
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
            Map<Long, List<FaceRecord>> loaded = faceDao.findSlimByNameIds(List.copyOf(missing));
            for (Long nameId : missing) {
                List<FaceRecord> faces = loaded.getOrDefault(nameId, List.of());
                if (!faces.isEmpty()) {
                    averages.put(nameId, faceSelector.averageOf(faces));
                }
            }
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

    /** Drops every entry when a face write happened since the last lookup. */
    private void syncWithWrites() {
        long current = faceDao.writeVersion();
        if (current != version) {
            averages.clear();
            version = current;
        }
    }
}
