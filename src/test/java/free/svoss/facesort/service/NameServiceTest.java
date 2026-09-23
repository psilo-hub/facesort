package free.svoss.facesort.service;

import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.model.NameRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NameService}, the shared trim/find/insert helper behind
 * {@code createOrFindName}/{@code findName} in the naming workflows.
 */
class NameServiceTest {

    private Database db;
    private NameService service;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        service = new NameService(new NameDao(db.getConnection()));
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    @Test
    void createOrFindName_createsTrimsAndInsertsNewName() throws Exception {
        long id = service.createOrFindName("  Alice  ");
        assertTrue(id > 0);
        assertEquals("Alice", service.findName("Alice").orElseThrow().name(),
                "the stored name must be the trimmed value");
    }

    @Test
    void createOrFindName_reusesExistingNameId() throws Exception {
        long alice = service.createOrFindName("Alice");
        assertEquals(alice, service.createOrFindName("Alice"));
        assertEquals(alice, service.createOrFindName("  Alice  "),
                "whitespace around a known name must not create a duplicate");
    }

    @Test
    void createOrFindName_rejectsBlankAndNull() {
        assertThrows(IllegalArgumentException.class, () -> service.createOrFindName("   "));
        assertThrows(NullPointerException.class, () -> service.createOrFindName(null));
    }

    @Test
    void findName_trimsTheLookup() throws Exception {
        service.createOrFindName("Alice");
        NameRecord found = service.findName("  Alice  ").orElseThrow();
        assertEquals("Alice", found.name());
    }

    @Test
    void findName_unknownNameReturnsEmpty() throws Exception {
        assertTrue(service.findName("Nobody").isEmpty());
    }
}