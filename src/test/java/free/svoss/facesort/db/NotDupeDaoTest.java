package free.svoss.facesort.db;

import free.svoss.facesort.model.NamePair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link NotDupeDao} against an in-memory SQLite database.
 */
class NotDupeDaoTest {

    private Database db;
    private NotDupeDao dao;
    private NameDao nameDao;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        dao = new NotDupeDao(db.getConnection());
        nameDao = new NameDao(db.getConnection());
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private long insertName(String name) throws Exception {
        return nameDao.insert(name);
    }

    private boolean isNotDupe(long nameIdA, long nameIdB) throws Exception {
        long a = Math.min(nameIdA, nameIdB);
        long b = Math.max(nameIdA, nameIdB);
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "SELECT 1 FROM not_dupes WHERE name_id_a = ? AND name_id_b = ?")) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            return ps.executeQuery().next();
        }
    }

    @Test
    void insert_isNotDupe_orderInsensitive() throws Exception {
        long a = insertName("Alice");
        long b = insertName("Bob");

        dao.insert(a, b);

        assertTrue(isNotDupe(a, b), "forward order must be detected");
        assertTrue(isNotDupe(b, a), "reverse order must be detected (normalized storage)");
        assertFalse(isNotDupe(a, insertName("Carol")), "untracked pair must not be a not-dupe");
    }

    @Test
    void insert_reversedOrder_storedOnce() throws Exception {
        long a = insertName("Alice");
        long b = insertName("Bob");

        dao.insert(b, a); // reversed

        List<NamePair> all = dao.findAll();
        assertEquals(1, all.size(), "reversed insert must not create a duplicate row");
        assertEquals(new NamePair(a, b), all.get(0),
                "pair must be stored normalized as (min, max)");
    }

    @Test
    void findAll_returnsAllPairs() throws Exception {
        long a = insertName("Alice");
        long b = insertName("Bob");
        long c = insertName("Carol");
        dao.insert(a, b);
        dao.insert(a, c);

        assertEquals(2, dao.findAll().size());
    }

    @Test
    void deleteName_cascadesToNotDupes() throws Exception {
        long a = insertName("Alice");
        long b = insertName("Bob");
        long c = insertName("Carol");
        dao.insert(a, b);
        dao.insert(a, c);
        dao.insert(b, c);

        nameDao.delete(a);

        // Rows referencing Alice must be cascade-deleted; the (b, c) row
        // references only Bob and Carol and must survive.
        assertFalse(isNotDupe(a, b));
        assertFalse(isNotDupe(a, c));
        assertTrue(isNotDupe(b, c), "pair not involving the deleted name survives");
        assertEquals(1, dao.findAll().size());
    }

    @Test
    void insert_samePairTwice_isIdempotent() throws Exception {
        long a = insertName("Alice");
        long b = insertName("Bob");
        dao.insert(a, b);
        dao.insert(b, a); // duplicate in normalized form -> INSERT OR IGNORE

        assertEquals(1, dao.findAll().size());
    }
}