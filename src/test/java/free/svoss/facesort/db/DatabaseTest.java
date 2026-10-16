package free.svoss.facesort.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Database}: the connection's journal and locking settings, and
 * schema versioning through {@code PRAGMA user_version}.
 */
class DatabaseTest {

    @TempDir
    Path tempDir;

    @Test
    void freshDatabase_isStampedWithCurrentSchemaVersion() throws Exception {
        try (Database db = Database.inMemory()) {
            assertEquals(Database.SCHEMA_VERSION, userVersion(db));
        }
    }

    @Test
    void freshDatabase_createsAllTablesAndIndexes() throws Exception {
        try (Database db = Database.inMemory()) {
            try (Statement stmt = db.getConnection().createStatement()) {
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'images'"));
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'faces'"));
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'videos'"));
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_faces_name_id'"));
            }
        }
    }

    @Test
    void freshDatabase_createsThePathPrefixIndexes() throws Exception {
        try (Database db = Database.inMemory()) {
            try (Statement stmt = db.getConnection().createStatement()) {
                assertEquals(List.of("path"), indexedColumns(stmt, "idx_image_paths_path"),
                        "the folder filter of the tagging views needs an index on image_paths.path");
                assertEquals(List.of("path"), indexedColumns(stmt, "idx_video_paths_path"),
                        "the folder filter and the removal-by-prefix need an index on video_paths.path");
            }
        }
    }

    @Test
    void freshDatabase_createsTheNotFacesTable() throws Exception {
        try (Database db = Database.inMemory()) {
            try (Statement stmt = db.getConnection().createStatement()) {
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'not_faces'"),
                        "faces dismissed by the user are recorded in their own table");
            }
        }
    }

    @Test
    void freshDatabase_createsTheMetaTable() throws Exception {
        try (Database db = Database.inMemory()) {
            try (Statement stmt = db.getConnection().createStatement()) {
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'meta'"),
                        "bookkeeping flags that are not user data live in their own key/value table");
            }
        }
    }

    @Test
    void freshDatabase_createsTheNotMatchesTable() throws Exception {
        try (Database db = Database.inMemory()) {
            try (Statement stmt = db.getConnection().createStatement()) {
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'not_matches'"),
                        "face/name combinations the user rejected as 'not the same person' are recorded in their own table");
            }
        }
    }

    @Test
    void databaseFromVersionFive_gainsTheNotMatchesTable() throws Exception {
        Path dbFile = tempDir.resolve("v5.db");
        try (Database db = new Database(dbFile)) {
            try (Statement stmt = db.getConnection().createStatement()) {
                // Recreate the version-5 schema: the same tables, no not_matches table.
                stmt.execute("DROP TABLE not_matches");
                stmt.execute("PRAGMA user_version = 5");
            }
        }

        try (Database migrated = new Database(dbFile)) {
            assertEquals(Database.SCHEMA_VERSION, userVersion(migrated),
                    "a version-5 database must be migrated to the current schema version");
            try (Statement stmt = migrated.getConnection().createStatement()) {
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'not_matches'"),
                        "an existing library must gain the not_matches table on the next open");
            }
        }
    }

    @Test
    void databaseFromVersionThree_gainsTheNotFacesTable() throws Exception {
        Path dbFile = tempDir.resolve("v3.db");
        try (Database db = new Database(dbFile)) {
            try (Statement stmt = db.getConnection().createStatement()) {
                // Recreate the version-3 schema: the same tables, no not_faces table.
                stmt.execute("DROP TABLE not_faces");
                stmt.execute("PRAGMA user_version = 3");
            }
        }

        try (Database migrated = new Database(dbFile)) {
            assertEquals(Database.SCHEMA_VERSION, userVersion(migrated),
                    "a version-3 database must be migrated to the current schema version");
            try (Statement stmt = migrated.getConnection().createStatement()) {
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'not_faces'"),
                        "an existing library must gain the not_faces table on the next open");
            }
        }
    }

    @Test
    void databaseFromVersionFour_gainsTheMetaTable() throws Exception {
        Path dbFile = tempDir.resolve("v4.db");
        try (Database db = new Database(dbFile)) {
            try (Statement stmt = db.getConnection().createStatement()) {
                // Recreate the version-4 schema: the same tables, no meta table.
                stmt.execute("DROP TABLE meta");
                stmt.execute("PRAGMA user_version = 4");
            }
        }

        try (Database migrated = new Database(dbFile)) {
            assertEquals(Database.SCHEMA_VERSION, userVersion(migrated),
                    "a version-4 database must be migrated to the current schema version");
            try (Statement stmt = migrated.getConnection().createStatement()) {
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'meta'"),
                        "an existing library must gain the meta table on the next open");
            }
        }
    }

    @Test
    void databaseFromVersionTwo_gainsThePathPrefixIndexes() throws Exception {
        Path dbFile = tempDir.resolve("v2.db");
        try (Database db = new Database(dbFile)) {
            try (Statement stmt = db.getConnection().createStatement()) {
                // Recreate the version-2 schema: the same tables, no path indexes.
                stmt.execute("DROP INDEX idx_image_paths_path");
                stmt.execute("DROP INDEX idx_video_paths_path");
                stmt.execute("PRAGMA user_version = 2");
            }
        }

        try (Database migrated = new Database(dbFile)) {
            assertEquals(Database.SCHEMA_VERSION, userVersion(migrated),
                    "a version-2 database must be migrated to the current schema version");
            try (Statement stmt = migrated.getConnection().createStatement()) {
                assertEquals(List.of("path"), indexedColumns(stmt, "idx_image_paths_path"),
                        "an existing library must gain the path index on the next open");
                assertEquals(List.of("path"), indexedColumns(stmt, "idx_video_paths_path"),
                        "an existing library must gain the path index on the next open");
            }
        }
    }

    @Test
    void reopen_isIdempotent_keepsVersionAndData() throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        try (Database db = new Database(dbFile)) {
            try (Statement stmt = db.getConnection().createStatement()) {
                stmt.execute("INSERT INTO images (hash) VALUES ('abc')");
            }
        }

        try (Database reopened = new Database(dbFile)) {
            assertEquals(Database.SCHEMA_VERSION, userVersion(reopened));
            try (Statement stmt = reopened.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM images WHERE hash = 'abc'")) {
                assertTrue(rs.next() && rs.getInt(1) == 1, "data must survive a reopen");
            }
        }
    }

    @Test
    void databaseFromOlderBuildWithoutVersionStamp_isMigratedToCurrentVersion() throws Exception {
        Path dbFile = tempDir.resolve("old.db");
        try (Database db = new Database(dbFile)) {
            db.getConnection().createStatement().execute("PRAGMA user_version = 0");
        }

        try (Database migrated = new Database(dbFile)) {
            assertEquals(Database.SCHEMA_VERSION, userVersion(migrated));
            try (Statement stmt = migrated.getConnection().createStatement()) {
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'images'"),
                        "missing tables from older builds must be created");
            }
        }
    }

    @Test
    void databaseFromVersionOne_dropsDeadCountColumns() throws Exception {
        Path dbFile = tempDir.resolve("v1.db");
        try (Database db = new Database(dbFile)) {
            try (Statement stmt = db.getConnection().createStatement()) {
                // Recreate the version-1 schema with the stored count columns
                // that version 2 removes.
                stmt.execute("DROP TABLE video_frames");
                stmt.execute("DROP TABLE videos");
                stmt.execute("""
                        CREATE TABLE videos (
                            hash            TEXT PRIMARY KEY,
                            detection_ts    INTEGER,
                            criteria_json   TEXT,
                            duration_secs   REAL,
                            frame_count     INTEGER DEFAULT 0,
                            face_count      INTEGER DEFAULT 0
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE video_frames (
                            frame_hash      TEXT PRIMARY KEY,
                            video_hash      TEXT NOT NULL,
                            timestamp_ms    INTEGER NOT NULL,
                            face_count      INTEGER DEFAULT 0,
                            FOREIGN KEY (frame_hash) REFERENCES images(hash) ON DELETE CASCADE,
                            FOREIGN KEY (video_hash) REFERENCES videos(hash) ON DELETE CASCADE
                        )
                        """);
                stmt.execute("PRAGMA user_version = 1");
            }
        }

        try (Database migrated = new Database(dbFile)) {
            assertEquals(Database.SCHEMA_VERSION, userVersion(migrated), "v1 database must be migrated to v2");
            try (Statement stmt = migrated.getConnection().createStatement()) {
                assertEquals(0, columnCount(stmt, "videos", "frame_count"),
                        "videos.frame_count must be dropped");
                assertEquals(0, columnCount(stmt, "videos", "face_count"),
                        "videos.face_count must be dropped");
                assertEquals(0, columnCount(stmt, "video_frames", "face_count"),
                        "video_frames.face_count must be dropped");
            }
        }
    }

    @Test
    void newerSchemaVersion_isRefused() throws Exception {
        Path dbFile = tempDir.resolve("newer.db");
        try (Database db = new Database(dbFile)) {
            db.getConnection().createStatement().execute("PRAGMA user_version = 99");
        }

        try {
            new Database(dbFile);
        } catch (SchemaVersionTooNewException ex) {
            assertEquals(99, ex.foundVersion());
            assertEquals(Database.SCHEMA_VERSION, ex.supportedVersion());
        }

        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            assertTrue(rs.next());
            assertEquals(99, rs.getInt(1));
        }
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement()) {
            assertEquals(1, countRows(stmt, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'images'"));
        }
        Files.delete(dbFile);
    }

    @Test
    void migrationRollsBackOnFailure_andKeepsPreviousSchemaVersion() throws Exception {
        Path dbFile = tempDir.resolve("rollback.db");
        // Create v1 schema manually
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE videos (" +
                    "hash TEXT PRIMARY KEY, detection_ts INTEGER, criteria_json TEXT, duration_secs REAL, " +
                    "frame_count INTEGER DEFAULT 0, face_count INTEGER DEFAULT 0)");
            stmt.execute("CREATE TABLE video_frames (" +
                    "frame_hash TEXT PRIMARY KEY, video_hash TEXT NOT NULL, timestamp_ms INTEGER NOT NULL, " +
                    "face_count INTEGER DEFAULT 0, " +
                    "FOREIGN KEY (frame_hash) REFERENCES images(hash) ON DELETE CASCADE, " +
                    "FOREIGN KEY (video_hash) REFERENCES videos(hash) ON DELETE CASCADE)");
            // Add minimal tables referenced by FKs if needed for schema sanity
            stmt.execute("CREATE TABLE images (hash TEXT PRIMARY KEY, detection_ts INTEGER, criteria_json TEXT, face_count INTEGER DEFAULT 0)");
            stmt.execute("CREATE INDEX idx_videos_face_count ON videos(face_count)");
            stmt.execute("PRAGMA user_version = 1");
        }

        try {
            new Database(dbFile);
        } catch (Exception ex) {
            // Expected
        }

        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement()) {
            assertEquals(1, countRows(stmt, "SELECT count(*) FROM pragma_table_info('videos') WHERE name='face_count'"));
        }
        Files.delete(dbFile);
    }

    @Test
    void deleteImageAndVideo_cascadesToDependentRows() throws Exception {
        try (Database db = Database.inMemory()) {
            try (Statement stmt = db.getConnection().createStatement()) {
                stmt.execute("INSERT INTO images (hash, detection_ts, criteria_json, face_count) VALUES ('img1', 0, '{}', 1)");
                stmt.execute("INSERT INTO images (hash, detection_ts, criteria_json, face_count) VALUES ('frameImg', 0, '{}', 1)");
                stmt.execute("INSERT INTO image_paths (hash, path) VALUES ('img1', '/p/a.jpg')");
                stmt.execute("INSERT INTO thumbnails (hash, jpg_data) VALUES ('img1', X'FF')");
                stmt.execute("INSERT INTO names (name) VALUES ('Alice')");
                stmt.execute("""
                        INSERT INTO faces (image_hash, bbox_x, bbox_y, bbox_w, bbox_h,
                            confidence, embedding, sub_image_jpg, name_id)
                        VALUES ('img1', 10, 10, 80, 80, 0.9, X'00', X'FF',
                            (SELECT id FROM names WHERE name = 'Alice'))
                        """);
                stmt.execute("INSERT INTO not_faces (face_id) SELECT id FROM faces WHERE image_hash = 'img1'");
                stmt.execute("INSERT INTO videos (hash, detection_ts, criteria_json, duration_secs) VALUES ('vid1', 0, '{}', 2.5)");
                stmt.execute("INSERT INTO video_paths (hash, path) VALUES ('vid1', '/v/p.mp4')");
                stmt.execute("INSERT INTO video_frames (frame_hash, video_hash, timestamp_ms) VALUES ('frameImg', 'vid1', 500)");

                stmt.execute("DELETE FROM images WHERE hash = 'img1'");

                assertEquals(0, countRows(stmt, "SELECT count(*) FROM image_paths WHERE hash = 'img1'"));
                assertEquals(0, countRows(stmt, "SELECT count(*) FROM thumbnails WHERE hash = 'img1'"));
                assertEquals(0, countRows(stmt, "SELECT count(*) FROM faces WHERE image_hash = 'img1'"));
                assertEquals(0, countRows(stmt, "SELECT count(*) FROM not_faces"),
                        "a dismissal must not outlive the face it dismissed");
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM names WHERE name = 'Alice'"),
                        "names are reached via ON DELETE SET NULL and must survive");

                stmt.execute("DELETE FROM videos WHERE hash = 'vid1'");

                assertEquals(0, countRows(stmt, "SELECT count(*) FROM video_paths WHERE hash = 'vid1'"));
                assertEquals(0, countRows(stmt, "SELECT count(*) FROM video_frames WHERE video_hash = 'vid1'"));
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM images WHERE hash = 'frameImg'"),
                        "the frame image must survive the removal of its frame link");
            }
        }
    }

    @Test
    void deleteFrameImage_cascadesToVideoFramesOnly() throws Exception {
        try (Database db = Database.inMemory()) {
            try (Statement stmt = db.getConnection().createStatement()) {
                stmt.execute("INSERT INTO images (hash) VALUES ('frameImg')");
                stmt.execute("INSERT INTO videos (hash) VALUES ('vid1')");
                stmt.execute("INSERT INTO video_frames (frame_hash, video_hash, timestamp_ms) VALUES ('frameImg', 'vid1', 500)");

                stmt.execute("DELETE FROM images WHERE hash = 'frameImg'");

                assertEquals(0, countRows(stmt, "SELECT count(*) FROM video_frames WHERE frame_hash = 'frameImg'"));
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM videos WHERE hash = 'vid1'"),
                        "the video must survive the removal of its frame reference");
            }
        }
    }

    @Test
    void fileDatabase_runsInWriteAheadLogMode() throws Exception {
        Path dbFile = tempDir.resolve("wal.db");
        try (Database db = new Database(dbFile)) {
            assertEquals("wal", pragma(db, "journal_mode"),
                    "a reader must not block the writer, so the file database must use WAL");
        }
    }

    @Test
    void fileDatabase_waitsForABusyLockInsteadOfFailingImmediately() throws Exception {
        Path dbFile = tempDir.resolve("busy.db");
        try (Database db = new Database(dbFile)) {
            assertEquals(Database.BUSY_TIMEOUT_MILLIS, pragmaInt(db, "busy_timeout"),
                    "a concurrent write must wait for the lock instead of failing with "
                            + "SQLITE_BUSY; sqlite-jdbc happens to default to 3000, so this is "
                            + "pinned explicitly rather than left to the driver");
        }
    }

    @Test
    void fileDatabase_doesNotFsyncEveryCommit() throws Exception {
        Path dbFile = tempDir.resolve("sync.db");
        try (Database db = new Database(dbFile)) {
            assertEquals(1, pragmaInt(db, "synchronous"),
                    "synchronous must be NORMAL (1), which is the safe pairing for WAL and skips "
                            + "an fsync per commit");
        }
    }

    @Test
    void inMemoryDatabase_appliesTheSettingsThatApplyToIt() throws Exception {
        try (Database db = Database.inMemory()) {
            assertEquals(Database.BUSY_TIMEOUT_MILLIS, pragmaInt(db, "busy_timeout"),
                    "the busy timeout must be applied on the in-memory path too, so tests exercise "
                            + "the same configuration code as production");
            assertEquals(1, pragmaInt(db, "synchronous"));
            assertEquals("memory", pragma(db, "journal_mode"),
                    "SQLite keeps in-memory databases in memory journaling mode; WAL is a file "
                            + "setting and is expected to be a no-op here");
        }
    }

    @Test
    void walModeSurvivesReopen() throws Exception {
        Path dbFile = tempDir.resolve("reopen.db");
        try (Database db = new Database(dbFile)) {
            assertEquals("wal", pragma(db, "journal_mode"));
        }

        try (Database reopened = new Database(dbFile)) {
            assertEquals("wal", pragma(reopened, "journal_mode"),
                    "WAL is a persistent property of the database file, so reopening must not "
                            + "revert it");
        }
    }

    private static int userVersion(Database db) throws SQLException {
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static String pragma(Database db, String name) throws SQLException {
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA " + name)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static int pragmaInt(Database db, String name) throws SQLException {
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA " + name)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static int countRows(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static int columnCount(Statement stmt, String table, String column) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return 1;
                }
            }
        }
        return 0;
    }

    /**
     * Returns the indexed columns of the named index in index order — empty when
     * no such index exists, so one assertion covers both the index and the
     * column it exists for.
     */
    private static List<String> indexedColumns(Statement stmt, String index) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery("PRAGMA index_info(" + index + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        return columns;
    }
}