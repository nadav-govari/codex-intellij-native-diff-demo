package dev.codex.nativediff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.intellij.mcpserver.toolsets.general.AddPatchOperation;
import com.intellij.mcpserver.toolsets.general.DeletePatchOperation;
import com.intellij.mcpserver.toolsets.general.PatchApplyEngine;
import com.intellij.mcpserver.toolsets.general.PatchOperation;
import com.intellij.mcpserver.toolsets.general.UpdatePatchOperation;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatchApplyEngineCompatibilityTest {
    @Test
    void parsesAndAppliesHookStyleWholeFileUpdate() {
        String patch = """
                *** Begin Patch
                *** Update File: src/main.rs
                @@
                -old
                -line
                +new
                +line
                *** End Patch
                """;

        List<PatchOperation> operations = PatchApplyEngine.INSTANCE.parsePatch(patch);
        UpdatePatchOperation update = assertInstanceOf(UpdatePatchOperation.class, operations.getFirst());
        assertEquals("src/main.rs", update.getPath());
        assertEquals("new\nline\n", PatchApplyEngine.INSTANCE.applyHunks("old\nline\n", update.getHunks()));
    }

    @Test
    void parsesAddDeleteAndMove() {
        String patch = """
                *** Begin Patch
                *** Add File: new.txt
                +hello
                *** Delete File: gone.txt
                *** Update File: old.txt
                *** Move to: moved.txt
                @@
                 same
                *** End Patch
                """;

        List<PatchOperation> operations = PatchApplyEngine.INSTANCE.parsePatch(patch);
        AddPatchOperation add = assertInstanceOf(AddPatchOperation.class, operations.get(0));
        assertEquals("hello\n", add.getContent());
        assertInstanceOf(DeletePatchOperation.class, operations.get(1));
        UpdatePatchOperation move = assertInstanceOf(UpdatePatchOperation.class, operations.get(2));
        assertEquals("moved.txt", move.getMoveTo());
        assertEquals("same\n", PatchApplyEngine.INSTANCE.applyHunks("same\n", move.getHunks()));
    }
}
