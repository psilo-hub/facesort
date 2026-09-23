package free.svoss.facesort.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Database}: schema versioning through {@code PRAGMA user_version}.
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
    void newerSchemaVersion_isToleratedWithoutDroppingAnything() throws Exception {
        Path dbFile = tempDir.resolve("newer.db");
        try (Database db = new Database(dbFile)) {
            db.getConnection().createStatement().execute("PRAGMA user_version = 99");
        }

        try (Database reopened = new Database(dbFile)) {
            assertEquals(99, userVersion(reopened), "a newer version must not be downgraded");
            try (Statement stmt = reopened.getConnection().createStatement()) {
                assertEquals(1, countRows(stmt, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'images'"),
                        "existing tables must survive opening a newer database");
            }
        }
    }

    private static int userVersion(Database db) throws SQLException {
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
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
}