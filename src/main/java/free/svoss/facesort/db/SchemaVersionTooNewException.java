package free.svoss.facesort.db;

import java.sql.SQLException;

/**
 * Thrown when the database schema version is newer than the version this build
 * supports, so opening it would risk corrupting a library created by a newer
 * release.
 */
public class SchemaVersionTooNewException extends SQLException {
    private final int foundVersion;
    private final int supportedVersion;

    public SchemaVersionTooNewException(int foundVersion, int supportedVersion) {
        super("Database schema version " + foundVersion + " is newer than the supported version " + supportedVersion);
        this.foundVersion = foundVersion;
        this.supportedVersion = supportedVersion;
    }

    public int foundVersion() {
        return foundVersion;
    }

    public int supportedVersion() {
        return supportedVersion;
    }
}
