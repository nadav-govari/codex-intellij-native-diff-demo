package dev.codex.nativediff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathPolicyTest {
    @TempDir Path root;

    @Test
    void acceptsProjectRelativePath() throws Exception {
        assertEquals(root.toRealPath().resolve("src/Main.java"), PathPolicy.resolveSafe(root, "src/Main.java"));
    }

    @Test
    void rejectsTraversalAndGitMetadata() {
        assertThrows(IOException.class, () -> PathPolicy.resolveSafe(root, "../outside"));
        assertThrows(IOException.class, () -> PathPolicy.resolveSafe(root, ".git/config"));
    }

    @Test
    void rejectsEscapingSymlink() throws Exception {
        Path outside = Files.createTempDirectory("native-diff-outside");
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
            assertThrows(IOException.class, () -> PathPolicy.resolveSafe(root, "escape/file.txt"));
        } finally {
            Files.deleteIfExists(root.resolve("escape"));
            Files.deleteIfExists(outside);
        }
    }
}
