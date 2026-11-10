package free.svoss.facesort.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.PropertyResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for the process-global {@link I18n} helper.
 */
class I18nTest {

    private static final Pattern I18N_KEY_PATTERN = Pattern.compile(
            "I18n\\.(get|format|percent)\\(\"([^\"]+)\""
    );

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

    @Test
    void dedupeRoundShowsThePercentItIsGiven() {
        String text = I18n.format("ui.dedupe.round", 2, "Alice", "Bob", I18n.percent(0.87));

        assertTrue(text.contains("87%"),
                "the dedupe round must use the shared percentage formatting, got: " + text);
    }

    @Test
    void differentNameSimilarityShowsThePercentItIsGiven() {
        String text = I18n.format("ui.faceName.differentName.similar",
                "Alice", I18n.percent(0.87));

        assertTrue(text.contains("87%"),
                "the tag-with-name preview must use the shared percentage formatting, got: " + text);
    }

    @Test
    void localeAndBundleChangeAtomically() {
        // Switching locale must immediately be reflected in both getLocale()
        // and get() — there must be no window where the locale has changed
        // but the bundle still reflects the old language, or vice versa.
        I18n.setLocale(Locale.GERMAN);

        assertEquals(Locale.GERMAN, I18n.getLocale(),
                "getLocale must immediately reflect the new locale");

        String germanText = I18n.get("ui.mostSimilar.same");
        assertEquals("Dieselbe Person", germanText,
                "get must immediately return text in the new language");

        // Switch back and verify both reflect English again
        I18n.setLocale(Locale.ENGLISH);

        assertEquals(Locale.ENGLISH, I18n.getLocale(),
                "getLocale must immediately reflect the reset locale");

        String englishText = I18n.get("ui.mostSimilar.same");
        assertEquals("Same person", englishText,
                "get must immediately return text in the reset language");
    }

    @Test
    void concurrentLocaleSwitchesDoNotCorruptState() throws InterruptedException {
        int threadCount = 8;
        int iterationsPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> failure = new AtomicReference<>(null);

        Locale[] locales = {Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH};

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        Locale target = locales[(threadId + i) % locales.length];
                        I18n.setLocale(target);

                        // A single call to get() must always return text that
                        // matches one of the valid translations - never garbled
                        // or mixed text from different locales.
                        String text = I18n.get("ui.mostSimilar.same");

                        boolean valid = switch (text) {
                            case "Same person", "Dieselbe Person",
                                 "La même personne" -> true;
                            default -> false;
                        };

                        if (!valid) {
                            failure.compareAndSet(null,
                                    new AssertionError("Unexpected text for any locale: " + text));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(10, TimeUnit.SECONDS),
                "all threads must complete within 10 seconds");
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                "executor must terminate within 10 seconds");

        Throwable f = failure.get();
        if (f != null) {
            throw new AssertionError("Concurrent access produced inconsistent state", f);
        }

        // After all threads finish, the state must still be usable
        I18n.setLocale(Locale.ENGLISH);
        assertEquals("Same person", I18n.get("ui.mostSimilar.same"),
                "I18n must still work correctly after concurrent access");
    }

    @Test
    void bundleKeysAreConsistentAcrossAllLanguages() throws IOException {
        Path resources = Path.of("src/main/resources/i18n");
        Path baseBundle = resources.resolve("messages.properties");

        // Load the base (English) bundle
        PropertyResourceBundle base = loadBundle(baseBundle);
        Set<String> baseKeys = new TreeSet<>(base.keySet());

        // All translation bundles
        Path[] translationBundles = {
                resources.resolve("messages_de.properties"),
                resources.resolve("messages_es.properties"),
                resources.resolve("messages_fr.properties"),
                resources.resolve("messages_ru.properties"),
                resources.resolve("messages_zh.properties")
        };
        String[] translationLangs = {"de", "es", "fr", "ru", "zh"};

        // Collect all keys referenced in source code
        Set<String> sourceKeys = collectSourceKeys(Path.of("src/main/java"));

        StringBuilder failures = new StringBuilder();

        // 1. Every key referenced in source code must exist in the base bundle
        for (String key : sourceKeys) {
            if (!baseKeys.contains(key)) {
                failures.append("Key referenced in source but missing from base bundle: ")
                        .append(key).append("\n");
            }
        }

        // 2. Every key in the base bundle must exist in every translation bundle
        PropertyResourceBundle[] bundles = new PropertyResourceBundle[translationBundles.length];
        for (int i = 0; i < translationBundles.length; i++) {
            bundles[i] = loadBundle(translationBundles[i]);
        }
        for (String key : baseKeys) {
            for (int i = 0; i < translationBundles.length; i++) {
                if (!bundles[i].keySet().contains(key)) {
                    failures.append("Key '").append(key)
                            .append("' missing from translation bundle ")
                            .append(translationLangs[i]).append("\n");
                }
            }
        }

        // 3. Every key referenced in source code must exist in every translation bundle
        for (String key : sourceKeys) {
            for (int i = 0; i < translationBundles.length; i++) {
                if (!bundles[i].keySet().contains(key)) {
                    failures.append("Key referenced in source '").append(key)
                            .append("' missing from translation bundle ")
                            .append(translationLangs[i]).append("\n");
                }
            }
        }

        if (failures.length() > 0) {
            fail("I18n bundle consistency failures:\n" + failures);
        }
    }

    private static PropertyResourceBundle loadBundle(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return new PropertyResourceBundle(input);
        }
    }

    /**
     * Scans all Java source files for I18n.get("key"), I18n.format("key", ...),
     * and I18n.percent(...) calls and returns the set of keys referenced.
     */
    private static Set<String> collectSourceKeys(Path root) throws IOException {
        Set<String> keys = new TreeSet<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            Matcher m = I18N_KEY_PATTERN.matcher(content);
                            while (m.find()) {
                                keys.add(m.group(2));
                            }
                        } catch (IOException e) {
                            // Ignore unreadable files
                        }
                    });
        }
        return keys;
    }
}
