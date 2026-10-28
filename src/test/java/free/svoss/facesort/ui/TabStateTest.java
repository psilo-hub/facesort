package free.svoss.facesort.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link TabState}.
 */
class TabStateTest {

    @Test
    void takeLastTaggedName_returnsAndClearsThePendingName() {
        TabState state = new TabState();
        state.setLastTaggedName("Alice");

        assertEquals("Alice", state.takeLastTaggedName());
        assertNull(state.takeLastTaggedName(), "the name is consumed exactly once");
    }

    @Test
    void takeLastTaggedName_returnsNullWhenNothingWasSet() {
        TabState state = new TabState();

        assertNull(state.takeLastTaggedName());
    }

    @Test
    void setLastTaggedName_overwritesThePreviousPendingName() {
        TabState state = new TabState();
        state.setLastTaggedName("Alice");
        state.setLastTaggedName("Bob");

        assertEquals("Bob", state.takeLastTaggedName());
    }

    @Test
    void setLastPathFilter_remembersTheLastNonBlankFilter() {
        TabState state = new TabState();
        state.setLastPathFilter("/photos/alice");
        state.setLastPathFilter("/photos/bob");

        assertEquals("/photos/bob", state.getLastPathFilter());
    }

    @Test
    void setLastPathFilter_ignoresBlankValues() {
        TabState state = new TabState();
        state.setLastPathFilter("/photos/alice");
        state.setLastPathFilter("   ");

        assertEquals("/photos/alice", state.getLastPathFilter());
    }

    @Test
    void getLastPathFilter_returnsNullWhenNothingWasSet() {
        TabState state = new TabState();

        assertNull(state.getLastPathFilter());
    }
}
