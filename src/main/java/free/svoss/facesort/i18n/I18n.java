package free.svoss.facesort.i18n;

import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal internationalization helper backed by {@code i18n/messages*.properties}
 * resource bundles on the classpath.
 *
 * <p>The active language is process-global. {@link #get(String)} returns the
 * message for the active language (falling back to English when a key is
 * missing) and {@link #format(String, Object...)} fills
 * {@link String#format}-style placeholders using the active locale.
 * {@link #setLocale(Locale)} switches the language at runtime; views that need
 * to reflect the new language are rebuilt by the application when the user
 * changes the language.</p>
 *
 * <p>Locale and resource bundle are updated atomically as an immutable
 * {@link BundleState}, so readers always see a consistent pair and no
 * coarse-grained locking is required.</p>
 */
public final class I18n {

    private static final String BASE_NAME = "i18n.messages";
    private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    /**
     * An immutable snapshot of the active locale and its resource bundle.
     * Published atomically so readers never see a locale/bundle mismatch.
     */
    private record BundleState(Locale locale, ResourceBundle bundle) {
    }

    /** English never changes; it is the fallback for missing keys and locales. */
    private static final ResourceBundle BASE_BUNDLE = loadBundle(DEFAULT_LOCALE, DEFAULT_LOCALE);

    /** The currently active locale and its resource bundle, updated atomically. */
    private static final AtomicReference<BundleState> state =
            new AtomicReference<>(new BundleState(DEFAULT_LOCALE, BASE_BUNDLE));

    private I18n() {
        // Utility class: static members only.
    }

    /**
     * Returns the message for the active language.
     *
     * @param key the message key; must not be null
     * @return the translated text, or the English text (or the key itself)
     *         when no translation exists
     */
    public static String get(String key) {
        BundleState current = state.get();
        return resolveText(current, key);
    }

    /**
     * Returns the message for the active language with {@code %s}-style
     * placeholders filled in using the active locale.
     *
     * @param key  the message key; must not be null
     * @param args the substitution arguments referenced by the placeholders
     * @return the formatted text
     */
    public static String format(String key, Object... args) {
        BundleState current = state.get();
        return String.format(current.locale(), resolveText(current, key), args);
    }

    /**
     * Formats a fraction as a percentage rounded to a whole percent using the
     * active locale, for example {@code percent(0.426)} returns {@code "43%"}.
     *
     * @param fraction the fraction to format ({@code 0.0} to {@code 1.0})
     * @return the localized percentage string
     */
    public static String percent(double fraction) {
        BundleState current = state.get();
        return String.format(current.locale(), "%.0f%%", fraction * 100);
    }

    /**
     * Switches the active language. Affects all subsequent {@link #get(String)}
     * and {@link #format(String, Object...)} calls. The locale and its bundle
     * are updated atomically, so readers never see a mix of old and new.
     *
     * @param locale the language to switch to; {@code null} resets to English
     */
    public static void setLocale(Locale locale) {
        Locale resolved = locale == null ? DEFAULT_LOCALE : locale;
        state.set(new BundleState(resolved, loadBundle(resolved, DEFAULT_LOCALE)));
    }

    /**
     * Returns the currently active language.
     *
     * @return the active locale (never {@code null})
     */
    public static Locale getLocale() {
        return state.get().locale();
    }

    /**
     * Resolves a language code into a {@link Locale}. Unknown or blank codes
     * fall back to English.
     *
     * @param code the language code (e.g. {@code en}, {@code zh}); may be null
     * @return the resolved locale (never {@code null})
     */
    public static Locale localeFor(String code) {
        if (code == null || code.isBlank()) {
            return DEFAULT_LOCALE;
        }
        return Locale.forLanguageTag(code.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the languages the application ships translations for.
     *
     * @return the supported languages with their native display names
     */
    public static List<Language> supportedLanguages() {
        return List.of(
                new Language("en", "English"),
                new Language("de", "Deutsch"),
                new Language("fr", "Français"),
                new Language("es", "Español"),
                new Language("ru", "Русский"),
                new Language("zh", "中文"));
    }

    private static ResourceBundle loadBundle(Locale locale, Locale fallback) {
        try {
            return ResourceBundle.getBundle(BASE_NAME, locale, new FallbackControl(fallback));
        } catch (MissingResourceException e) {
            return ResourceBundle.getBundle(BASE_NAME, Locale.ENGLISH);
        }
    }

    /**
     * Resolves the translated text for a key from a given bundle state.
     * Falls back to the English base bundle when the key is missing.
     */
    private static String resolveText(BundleState current, String key) {
        try {
            return current.bundle().getString(key);
        } catch (MissingResourceException e) {
            try {
                return BASE_BUNDLE.getString(key);
            } catch (MissingResourceException missing) {
                return key;
            }
        }
    }

    /**
     * A locale that never consults the JVM default locale, so the application's
     * language is exactly what the user selected.
     */
    private static final class FallbackControl extends ResourceBundle.Control {

        private final Locale fallback;

        FallbackControl(Locale fallback) {
            this.fallback = fallback;
        }

        @Override
        public List<Locale> getCandidateLocales(String baseName, Locale locale) {
            if (locale.getLanguage().isEmpty()) {
                return List.of(Locale.ROOT);
            }
            return List.of(locale);
        }

        @Override
        public Locale getFallbackLocale(String baseName, Locale locale) {
            return locale.getLanguage().equals(fallback.getLanguage()) ? null : fallback;
        }
    }

    /**
     * A language the application ships translations for.
     *
     * @param code        the language code used in the config file
     * @param displayName the language's native display name
     */
    public record Language(String code, String displayName) {
    }
}
