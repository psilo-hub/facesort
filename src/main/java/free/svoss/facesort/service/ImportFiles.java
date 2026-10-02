package free.svoss.facesort.service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Recursive file collection shared by the photo and video import services.
 * Both walk a folder tree the same way: regular files whose extension is in a
 * given set are collected, unreadable files are logged and skipped, and the
 * walk terminates as soon as the cancellation supplier reports {@code true}.
 */
final class ImportFiles {

    private static final Logger LOG = Logger.getLogger(ImportFiles.class.getName());

    private ImportFiles() {
        // Utility class - not instantiable
    }

    /**
     * Recursively collects all regular files under {@code root} that have one
     * of the given extensions.
     *
     * <p>The walk terminates as soon as {@code cancelled} reports {@code true},
     * potentially returning a partial list.</p>
     *
     * @param root       the root directory to scan
     * @param extensions supported file extensions (case-insensitive, without the
     *                   leading dot)
     * @param cancelled  supplier consulted before each file and directory; when
     *                   it returns {@code true} the scan stops (may be {@code null})
     * @return the collected files
     * @throws IOException if the folder cannot be traversed
     */
    static List<Path> collect(Path root, Set<String> extensions,
                              BooleanSupplier cancelled) throws IOException {
        return collectCategorized(root, extensions, cancelled).getOrDefault(extensions, List.of());
    }

    /**
     * Recursively collects all regular files under {@code root} grouped by the
     * extension set they match. When a file matches more than one set, it is
     * added to every matching set (extensions are disjoint in practice, but the
     * API allows it).
     *
     * @param root       the root directory to scan
     * @param extensions supported file extensions (case-insensitive, without the
     *                   leading dot)
     * @param cancelled  supplier consulted before each file and directory
     * @return map from the extension set to the list of matching files
     * @throws IOException if the folder cannot be traversed
     */
    static java.util.Map<Set<String>, List<Path>> collectCategorized(
            Path root, Set<String> extensions, BooleanSupplier cancelled) throws IOException {
        java.util.Map<Set<String>, List<Path>> wrapper = new java.util.HashMap<>();
        wrapper.put(extensions, List.of());
        java.util.Map<Set<String>, List<Path>> result =
                collectCategorizedSets(root, java.util.Set.of(extensions), cancelled);
        return result;
    }

    /**
     * Recursively collects all regular files under {@code root} grouped by which
     * extension set they match. The first set in iteration order that matches
     * the file's extension determines its primary bucket (if sets overlap, the
     * first matching set wins).
     */
    static java.util.Map<Set<String>, List<Path>> collectCategorizedSets(
            Path root, java.util.Set<Set<String>> extensionSets,
            BooleanSupplier cancelled) throws IOException {
        java.util.Map<Set<String>, List<Path>> result = new java.util.HashMap<>();
        for (Set<String> set : extensionSets) {
            result.put(set, new ArrayList<>());
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }
                if (attrs.isRegularFile()) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    int dot = name.lastIndexOf('.');
                    if (dot >= 0) {
                        String ext = name.substring(dot + 1);
                        for (Set<String> set : extensionSets) {
                            if (set.contains(ext)) {
                                result.get(set).add(file);
                                break;
                            }
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return (cancelled != null && cancelled.getAsBoolean())
                        ? FileVisitResult.TERMINATE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                LOG.log(Level.WARNING, "Cannot access file: " + file, exc);
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    /**
     * Returns {@code true} if the file has one of the supported extensions
     * (case-insensitive).
     */
    static boolean hasSupportedExtension(Path file, Set<String> extensions) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return extensions.contains(name.substring(dot + 1));
    }
}