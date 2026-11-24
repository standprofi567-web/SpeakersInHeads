package dev.klxsh.speakersinheads.music;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/** Canonical identity for path-based catalog entries, including Windows case aliases and hard links. */
final class PathIdentity {
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private PathIdentity() {}

    static Path normalize(Path path, Path gameDirectory) {
        Path absolute = path.isAbsolute() ? path : gameDirectory.resolve(path);
        return absolute.toAbsolutePath().normalize();
    }

    static String key(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            Path real = absolute.toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(real, BasicFileAttributes.class);
            Object fileKey = attributes.fileKey();
            if (fileKey != null) return "file:" + fileKey;
            absolute = real;
        } catch (IOException | SecurityException ignored) {
            // Keep a stable absolute identity for missing, unreadable, or not-yet-created paths.
        }
        String value = absolute.toString();
        return WINDOWS ? value.toLowerCase(Locale.ROOT) : value;
    }

    static boolean isWithin(Path path, Path directory) {
        Path child = path.toAbsolutePath().normalize();
        Path parent = directory.toAbsolutePath().normalize();
        if (!WINDOWS) return child.startsWith(parent);
        String childText = child.toString();
        String parentText = parent.toString();
        if (!childText.regionMatches(true, 0, parentText, 0, parentText.length())) return false;
        return childText.length() == parentText.length()
                || childText.charAt(parentText.length()) == '\\' || childText.charAt(parentText.length()) == '/';
    }
}
