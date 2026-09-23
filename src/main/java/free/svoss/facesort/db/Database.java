package free.svoss.facesort.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages SQLite database connection and schema initialization.
 */
public class Database implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Database.class.getName());

    /**
     * Latest schema version, stored in the SQLite {@code PRAGMA user_version}.
     * Old unversioned databases (version 0) are brought up to this version by
     * applying the idempotent baseline DDL.
     */
    public static final int SCHEMA_VERSION = 1;

    private final Connection connection;
    private final Connection sharedConnection;
    private final TransactionRunner transactionRunner;

    /**
     * Opens or creates a database at the given path.
     * Enables foreign keys and initializes the schema.
     */
    public Database(Path dbPath) throws SQLException {
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        this.connection = DriverManager.getConnection(url);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
        }
        initializeSchema();
        SynchronizedConnection synchronizing = new SynchronizedConnection(connection);
        this.sharedConnection = synchronizing.proxy();
        this.transactionRunner = new TransactionRunner(synchronizing);
    }

    /**
     * Opens an in-memory database (for testing).
     */
    public static Database inMemory() throws SQLException {
        Database db = new Database();
        return db;
    }

    private Database() throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
        }
        initializeSchema();
        SynchronizedConnection synchronizing = new SynchronizedConnection(connection);
        this.sharedConnection = synchronizing.proxy();
        this.transactionRunner = new TransactionRunner(synchronizing);
    }

    /**
     * Returns the JDBC connection used by the DAOs, wrapped in a
     * {@link SynchronizedConnection} proxy so that every call — from any
     * service or worker thread — is serialized behind a single monitor.
     * The SQLite connection is not thread-safe, but app and tests share it
     * across threads.
     */
    public Connection getConnection() {
        return sharedConnection;
    }

    /**
     * Returns the runner that executes multi-statement write units as single
     * transactions (see {@link TransactionRunner}).
     */
    public TransactionRunner getTransactionRunner() {
        return transactionRunner;
    }

    private void initializeSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            int version = userVersion(stmt);
            if (version > SCHEMA_VERSION) {
                LOG.log(Level.WARNING,
                        "Database schema version {0} is newer than the version this build supports "
                                + "({1}); opening without migrating",
                        new Object[]{version, SCHEMA_VERSION});
            } else if (version < SCHEMA_VERSION) {
                migrate(stmt, version);
                stmt.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            }
        }
    }

    /**
     * Returns the schema version recorded in {@code PRAGMA user_version}.
     * Databases created by older builds have no version stamp and report 0.
     */
    private static int userVersion(Statement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Brings the schema from {@code fromVersion} up to the current schema
     * version. Version 0 means either a brand-new database or an unversioned
     * database created by an older build, so the baseline DDL is applied; as
     * the DDL is written with {@code IF NOT EXISTS} throughout it safely
     * covers both cases. Future non-additive changes append further versioned
     * statements here, chained from the version they start at.
     */
    private static void migrate(Statement stmt, int fromVersion) throws SQLException {
        if (fromVersion <= 0) {
            createBaselineSchema(stmt);
        }
        // Future migrations, e.g.:
        // if (fromVersion < 2) { stmt.execute(...); }
    }

    private static void createBaselineSchema(Statement stmt) throws SQLException {
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS images (
                    hash            TEXT PRIMARY KEY,
                    detection_ts    INTEGER,
                    criteria_json   TEXT,
                    face_count      INTEGER DEFAULT 0
                )
                """);

        stmt.execute("""
                CREATE TABLE IF NOT EXISTS image_paths (
                    hash  TEXT NOT NULL,
                    path  TEXT NOT NULL,
                    PRIMARY KEY (hash, path),
                    FOREIGN KEY (hash) REFERENCES images(hash) ON DELETE CASCADE
                )
                """);

        stmt.execute("""
                CREATE TABLE IF NOT EXISTS thumbnails (
                    hash        TEXT PRIMARY KEY,
                    jpg_data    BLOB NOT NULL,
                    FOREIGN KEY (hash) REFERENCES images(hash) ON DELETE CASCADE
                )
                """);

        stmt.execute("""
                CREATE TABLE IF NOT EXISTS names (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE
                )
                """);

        stmt.execute("""
                CREATE TABLE IF NOT EXISTS faces (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    image_hash      TEXT NOT NULL,
                    bbox_x          INTEGER NOT NULL,
                    bbox_y          INTEGER NOT NULL,
                    bbox_w          INTEGER NOT NULL,
                    bbox_h          INTEGER NOT NULL,
                    confidence      REAL NOT NULL,
                    embedding       BLOB NOT NULL,
                    sub_image_jpg   BLOB NOT NULL,
                    name_id         INTEGER,
                    FOREIGN KEY (image_hash) REFERENCES images(hash) ON DELETE CASCADE,
                    FOREIGN KEY (name_id) REFERENCES names(id) ON DELETE SET NULL,
                    UNIQUE (image_hash, bbox_x, bbox_y, bbox_w, bbox_h)
                )
                """);

        stmt.execute("""
                CREATE TABLE IF NOT EXISTS not_dupes (
                    name_id_a  INTEGER NOT NULL,
                    name_id_b  INTEGER NOT NULL,
                    PRIMARY KEY (name_id_a, name_id_b),
                    FOREIGN KEY (name_id_a) REFERENCES names(id) ON DELETE CASCADE,
                    FOREIGN KEY (name_id_b) REFERENCES names(id) ON DELETE CASCADE
                )
                """);

        stmt.execute("""
                CREATE TABLE IF NOT EXISTS videos (
                    hash            TEXT PRIMARY KEY,
                    detection_ts    INTEGER,
                    criteria_json   TEXT,
                    duration_secs   REAL,
                    frame_count     INTEGER DEFAULT 0,
                    face_count      INTEGER DEFAULT 0
                )
                """);

        stmt.execute("""
                CREATE TABLE IF NOT EXISTS video_paths (
                    hash            TEXT NOT NULL,
                    path            TEXT NOT NULL,
                    PRIMARY KEY (hash, path),
                    FOREIGN KEY (hash) REFERENCES videos(hash) ON DELETE CASCADE
                )
                """);

        stmt.execute("""
                CREATE TABLE IF NOT EXISTS video_frames (
                    frame_hash      TEXT PRIMARY KEY,
                    video_hash      TEXT NOT NULL,
                    timestamp_ms    INTEGER NOT NULL,
                    face_count      INTEGER DEFAULT 0,
                    FOREIGN KEY (frame_hash) REFERENCES images(hash) ON DELETE CASCADE,
                    FOREIGN KEY (video_hash) REFERENCES videos(hash) ON DELETE CASCADE
                )
                """);

        stmt.execute("CREATE INDEX IF NOT EXISTS idx_faces_name_id ON faces(name_id)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_faces_image_hash ON faces(image_hash)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_video_frames_video_hash ON video_frames(video_hash)");
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
