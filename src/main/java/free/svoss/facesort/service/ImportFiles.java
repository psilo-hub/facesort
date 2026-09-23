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
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }
                if (attrs.isRegularFile() && hasSupportedExtension(file, extensions)) {
                    files.add(file);
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
        return files;
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