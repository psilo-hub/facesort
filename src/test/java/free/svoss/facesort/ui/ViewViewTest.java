package free.svoss.facesort.ui;

import free.svoss.facesort.model.FaceRecord;
import free.svoss.facesort.model.NameRecord;
import free.svoss.facesort.service.ViewService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure name-filter logic of {@link ViewView}.
 */
class ViewViewTest {

    private static ViewService.NameSummary summary(String name) {
        NameRecord nameRecord = new NameRecord(1, name, null);
        FaceRecord face = new FaceRecord(1, "img", 0, 0, 80, 80, 0.9,
                new float[]{1, 0, 0, 0, 0, 0, 0, 0}, new byte[]{1}, nameRecord.id());
        return new ViewService.NameSummary(nameRecord, 1, face);
    }

    private static final List<ViewService.NameSummary> ALL =
            List.of(summary("Alice"), summary("Bob"), summary("Carol"), summary("alicia"));

    @Test
    void blankFilterReturnsAllNamesInOrder() {
        assertEquals(ALL, ViewView.filterNames(ALL, ""));
        assertEquals(ALL, ViewView.filterNames(ALL, "   "));
    }

    @Test
    void nullFilterReturnsAllNames() {
        assertEquals(ALL, ViewView.filterNames(ALL, null));
    }

    @Test
    void matchingIsCaseInsensitiveSubstring() {
        List<ViewService.NameSummary> result = ViewView.filterNames(ALL, "ali");
        assertEquals(List.of("Alice", "alicia"), result.stream().map(s -> s.name().name()).toList());
    }

    @Test
    void filterTrimsSurroundingWhitespace() {
        List<ViewService.NameSummary> result = ViewView.filterNames(ALL, "  bOb  ");
        assertEquals(List.of("Bob"), result.stream().map(s -> s.name().name()).toList());
    }

    @Test
    void nonMatchingFilterReturnsEmptyList() {
        assertTrue(ViewView.filterNames(ALL, "zzz").isEmpty());
    }

    @Test
    void emptyListWithAnyFilterStaysEmpty() {
        assertTrue(ViewView.filterNames(List.of(), "a").isEmpty());
    }
}