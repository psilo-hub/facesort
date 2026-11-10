package free.svoss.facesort.service;

import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.model.FaceRecord;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Holds the slim unnamed faces in memory between scans so the "Most similar"
 * tab does not reload the whole unnamed pool on every decision.
 *
 * <p>The pool is populated lazily on first use. A version stamp (count of
 * unnamed faces plus their maximum id) is checked at the start of every
 * scan; when it changed, the pool is reloaded from the database. Individual
 * faces can be removed when they are tagged as same person or dismissed as
 * not a face.</p>
 *
 * <p>All access is synchronized so the pool can be read from the scan task
 * and updated from decision tasks on other threads.</p>
 */
final class UnnamedFacePool {

    private final FaceDao faceDao;
    private List<FaceRecord> pool = List.of();
    private long unnamedCount = -1;
    private long unnamedMaxId = -1;

    UnnamedFacePool(FaceDao faceDao) {
        this.faceDao = Objects.requireNonNull(faceDao, "faceDao");
    }

    /**
     * Returns the current unnamed pool, reloading it when the version stamp
     * changed since the last load.
     *
     * @return the unnamed slim faces, excluding those dismissed as "not a face"
     * @throws SQLException on database error
     */
    synchronized List<FaceRecord> get() throws SQLException {
        long count = faceDao.countUnnamed();
        long maxId = faceDao.maxUnnamedId();
        if (count != unnamedCount || maxId != unnamedMaxId) {
            pool = faceDao.findUnnamedSlim(null);
            unnamedCount = count;
            unnamedMaxId = maxId;
        }
        return pool;
    }

    /**
     * Removes a face from the pool, e.g. when it was tagged as same person or
     * dismissed as not a face.
     *
     * @param faceId id of the face to remove
     */
    synchronized void remove(long faceId) {
        pool = pool.stream().filter(face -> face.id() != faceId).toList();
    }

    /**
     * Clears the pool, forcing a full reload on the next {@link #get()}.
     * Used after bulk operations where incremental tracking is impractical.
     */
    synchronized void clear() {
        pool = List.of();
        unnamedCount = -1;
        unnamedMaxId = -1;
    }

    /** Returns the number of faces currently held in the pool (for tests). */
    synchronized int size() {
        return pool.size();
    }

    /**
     * Returns the current version stamp of the pool: {@code [unnamed count,
     * max unnamed id]}. Callers (for example an HNSW index built on top of the
     * pool) can compare this against a previously observed value to detect
     * whether the pool was reloaded.
     */
    synchronized long[] versionStamp() {
        return new long[]{unnamedCount, unnamedMaxId};
    }
}
