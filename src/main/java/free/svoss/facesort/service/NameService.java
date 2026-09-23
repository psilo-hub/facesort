package free.svoss.facesort.service;

import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.model.NameRecord;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared name lookup/creation helper behind the naming workflows.
 *
 * <p>Both {@link NamingService} ("Put a name to a face") and
 * {@link FaceToNameService} ("Put a face to a name") used to trim, look up and
 * insert names in their own near-identical copies; this class owns that logic
 * once so the trim/find/insert contract cannot drift between the two.</p>
 */
public final class NameService {

    private final NameDao nameDao;

    public NameService(NameDao nameDao) {
        this.nameDao = Objects.requireNonNull(nameDao, "nameDao");
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
}