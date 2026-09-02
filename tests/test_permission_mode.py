import importlib.util
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "native_diff_hook", ROOT / "scripts" / "native_diff_hook.py"
)
assert SPEC is not None and SPEC.loader is not None
HOOK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOOK)


class PermissionModeTest(unittest.TestCase):
    def _transcript(self, *sandbox_modes):
        directory = Path(self.addCleanupDirectory())
        path = directory / "rollout.jsonl"
        lines = []
        for sandbox_mode in sandbox_modes:
            lines.append(
                json.dumps(
                    {
                        "type": "turn_context",
                        "payload": {"sandbox_policy": {"type": sandbox_mode}},
                    }
                )
            )
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return str(path)

    def addCleanupDirectory(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        return temporary.name

    def test_default_mode_uses_latest_writable_turn_context(self):
        event = {
            "permission_mode": "default",
            "transcript_path": self._transcript("read-only", "workspace-write"),
        }
        self.assertFalse(HOOK._is_manual_mode(event))

    def test_default_mode_keeps_read_only_manual(self):
        event = {
            "permission_mode": "default",
            "transcript_path": self._transcript("workspace-write", "read-only"),
        }
        self.assertTrue(HOOK._is_manual_mode(event))

    def test_explicit_auto_mode_does_not_need_transcript(self):
        self.assertFalse(HOOK._is_manual_mode({"permission_mode": "acceptEdits"}))

    def test_missing_transcript_preserves_legacy_manual_fallback(self):
        self.assertTrue(HOOK._is_manual_mode({"permission_mode": "default"}))

    def test_malformed_tail_finds_previous_turn_context(self):
        path = Path(self._transcript("workspace-write"))
        with path.open("a", encoding="utf-8") as stream:
            stream.write("not-json\n")
        event = {"permission_mode": "default", "transcript_path": str(path)}
        self.assertFalse(HOOK._is_manual_mode(event))

    def test_auto_pre_tool_use_emits_no_native_diff_decision(self):
        event = {
            "permission_mode": "default",
            "transcript_path": self._transcript("workspace-write"),
            "tool_name": "apply_patch",
            "tool_input": {"command": "*** Begin Patch\n*** End Patch\n"},
        }
        output = io.StringIO()
        with redirect_stdout(output):
            HOOK.handle_pre_tool(event)
        self.assertEqual(output.getvalue(), "")


class ShellMutationTest(unittest.TestCase):
    def test_searching_for_apply_patch_is_not_authoring(self):
        direct, targets = HOOK._mutation_targets("rg -n 'apply_patch' scripts README.md")
        self.assertFalse(direct)
        self.assertEqual(targets, [])

    def test_quoted_angle_brackets_are_not_redirection(self):
        direct, targets = HOOK._mutation_targets("rg -n '<entry access=read>' rollout.jsonl")
        self.assertFalse(direct)
        self.assertEqual(targets, [])

    def test_redirection_is_authoring(self):
        direct, targets = HOOK._mutation_targets("printf value > src/generated.txt")
        self.assertTrue(direct)
        self.assertEqual(targets, ["src/generated.txt"])

    def test_apply_patch_command_is_authoring(self):
        direct, _ = HOOK._mutation_targets("apply_patch")
        self.assertTrue(direct)

    def test_apply_patch_argument_to_search_is_not_authoring(self):
        direct, _ = HOOK._mutation_targets("rg apply_patch scripts")
        self.assertFalse(direct)


if __name__ == "__main__":
    unittest.main()
