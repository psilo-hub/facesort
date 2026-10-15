package free.svoss.facesort.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link MetaDao} against an in-memory SQLite database.
 */
class MetaDaoTest {

    private Database db;
    private MetaDao dao;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        dao = new MetaDao(db.getConnection());
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    @Test
    void get_returnsEmptyForAnUnwrittenKey() throws Exception {
        assertTrue(dao.get("never-written").isEmpty(),
                "an unknown key must not report a value");
    }

    @Test
    void put_thenGet_returnsTheStoredValue() throws Exception {
        dao.put("face_rotation_migration", "2026-10-15");

        assertEquals("2026-10-15", dao.get("face_rotation_migration").orElseThrow());
    }

    @Test
    void put_overwritesThePreviousValue() throws Exception {
        dao.put("key", "first");
        dao.put("key", "second");

        assertEquals("second", dao.get("key").orElseThrow(),
                "writing a key twice must replace its value, not fail");
    }

    @Test
    void remove_deletesOnlyTheGivenKey() throws Exception {
        dao.put("keep", "a");
        dao.put("drop", "b");

        dao.remove("drop");

        assertFalse(dao.get("drop").isPresent());
        assertEquals("a", dao.get("keep").orElseThrow(), "other keys must survive");
    }

    @Test
    void remove_unknownKey_isANoOp() throws Exception {
        dao.remove("never-written");

        assertTrue(dao.get("never-written").isEmpty());
    }
}
