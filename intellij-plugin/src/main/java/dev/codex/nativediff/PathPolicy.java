package dev.codex.nativediff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class PathPolicy {
    private PathPolicy() {}

    static Path resolveSafe(Path root, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IOException("Patch path is empty");
        }
        if (relativePath.chars().anyMatch(ch -> ch < 32)) {
            throw new IOException("Patch path contains a control character");
        }
        Path supplied = Path.of(relativePath);
        if (supplied.isAbsolute()) {
            throw new IOException("Absolute patch paths are not allowed: " + relativePath);
        }

        Path canonicalRoot = root.toRealPath();
        Path candidate = canonicalRoot.resolve(supplied).normalize();
        if (!candidate.startsWith(canonicalRoot)) {
            throw new IOException("Patch path escapes the project: " + relativePath);
        }
        Path normalizedRelative = canonicalRoot.relativize(candidate);
        if (normalizedRelative.getNameCount() > 0
                && normalizedRelative.getName(0).toString().equals(".git")) {
            throw new IOException("Patch paths inside .git are not allowed");
        }

        Path ancestor = candidate;
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null || !ancestor.toRealPath().startsWith(canonicalRoot)) {
            throw new IOException("Patch path escapes through a symlink: " + relativePath);
        }
        if (Files.exists(candidate) && !candidate.toRealPath().startsWith(canonicalRoot)) {
            throw new IOException("Patch path escapes through a symlink: " + relativePath);
        }
        return candidate;
    }

    static String relativeUnix(Path root, Path path) throws IOException {
        return root.toRealPath().relativize(path.normalize()).toString().replace('\\', '/');
    }
}
