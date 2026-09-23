package free.svoss.facesort.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the process-global {@link I18n} helper.
 */
class I18nTest {

    @AfterEach
    void restoreDefaultLocale() {
        I18n.setLocale(Locale.ENGLISH);
    }

    @Test
    void percentFormatsExactValues() {
        assertEquals("0%", I18n.percent(0.0));
        assertEquals("50%", I18n.percent(0.5));
        assertEquals("100%", I18n.percent(1.0));
    }

    @Test
    void percentRoundsToWholePercent() {
        assertEquals("43%", I18n.percent(0.434));
        assertEquals("44%", I18n.percent(0.436));
        assertEquals("100%", I18n.percent(0.999));
    }

    @Test
    void percentFollowsTheActiveLocale() {
        assertEquals("100%", I18n.percent(0.999));
    }
}