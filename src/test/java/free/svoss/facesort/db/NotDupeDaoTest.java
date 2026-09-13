package free.svoss.facesort.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
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

    @Test
    void insert_isNotDupe_orderInsensitive() throws Exception {
        long a = insertName("Alice");
        long b = insertName("Bob");

        dao.insert(a, b);

        assertTrue(dao.isNotDupe(a, b), "forward order must be detected");
        assertTrue(dao.isNotDupe(b, a), "reverse order must be detected (normalized storage)");
        assertFalse(dao.isNotDupe(a, insertName("Carol")), "untracked pair must not be a not-dupe");
    }

    @Test
    void insert_reversedOrder_storedOnce() throws Exception {
        long a = insertName("Alice");
        long b = insertName("Bob");

        dao.insert(b, a); // reversed

        List<long[]> all = dao.findAll();
        assertEquals(1, all.size(), "reversed insert must not create a duplicate row");
        assertTrue(Arrays.equals(new long[]{a, b}, all.get(0)),
                "pair must be stored normalized as (min, max)");
    }

    @Test
    void findByNameId_returnsPairsInvolvingName() throws Exception {
        long a = insertName("Alice");
        long b = insertName("Bob");
        long c = insertName("Carol");
        dao.insert(a, b);
        dao.insert(a, c);

        List<long[]> pairs = dao.findByNameId(a);
        assertEquals(2, pairs.size(), "both pairs involving Alice are returned");

        // a pair not involving Alice must not appear
        long d = insertName("Dave");
        long e = insertName("Erin");
        dao.insert(d, e);
        assertEquals(2, dao.findByNameId(a).size());
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
    void deleteForName_removesPairsInvolvingName() throws Exception {
        long a = insertName("Alice");
        long b = insertName("Bob");
        long c = insertName("Carol");
        dao.insert(a, b);
        dao.insert(a, c);
        dao.insert(b, c);

        dao.deleteForName(a);

        assertFalse(dao.isNotDupe(a, b));
        assertFalse(dao.isNotDupe(a, c));
        assertTrue(dao.isNotDupe(b, c), "pair not involving the deleted name survives");
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
        assertFalse(dao.isNotDupe(a, b));
        assertFalse(dao.isNotDupe(a, c));
        assertTrue(dao.isNotDupe(b, c), "pair not involving the deleted name survives");
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