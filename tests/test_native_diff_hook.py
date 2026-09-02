import importlib.util
import json
import os
import tempfile
import unittest
import xml.etree.ElementTree as ET
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).parents[1] / "scripts" / "native_diff_hook.py"
SPEC = importlib.util.spec_from_file_location("native_diff_hook", SCRIPT)
hook = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(hook)


class EncodePatchTest(unittest.TestCase):
    def test_update_uses_reviewed_text(self):
        patch = hook.encode_patch(
            [
                {
                    "kind": "update",
                    "path": "src/main.rs",
                    "moveTo": None,
                    "baseText": "old\nline\n",
                    "editedText": "new\nline\n",
                }
            ]
        )
        self.assertEqual(
            patch,
            "*** Begin Patch\n"
            "*** Update File: src/main.rs\n"
            "@@\n"
            "-old\n-line\n+new\n+line\n"
            "*** End Patch\n",
        )

    def test_add_delete_and_move(self):
        patch = hook.encode_patch(
            [
                {
                    "kind": "add",
                    "path": "new.txt",
                    "baseText": "",
                    "editedText": "hello\n",
                },
                {
                    "kind": "delete",
                    "path": "gone.txt",
                    "baseText": "bye\n",
                    "editedText": "",
                },
                {
                    "kind": "move",
                    "path": "old.txt",
                    "moveTo": "new-name.txt",
                    "baseText": "same\n",
                    "editedText": "same\n",
                },
            ]
        )
        self.assertIn("*** Add File: new.txt\n+hello", patch)
        self.assertIn("*** Delete File: gone.txt", patch)
        self.assertIn("*** Move to: new-name.txt", patch)

    def test_noop_is_empty(self):
        self.assertEqual(
            hook.encode_patch(
                [
                    {
                        "kind": "update",
                        "path": "same.txt",
                        "baseText": "same",
                        "editedText": "same",
                    }
                ]
            ),
            "",
        )

    def test_control_character_path_is_rejected(self):
        with self.assertRaises(hook.HookError):
            hook.encode_patch(
                [{"kind": "add", "path": "bad\nname", "baseText": "", "editedText": "x"}]
            )


class ShellClassifierTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def classify(self, command):
        with mock.patch.object(hook, "_project_root", return_value=self.root), mock.patch.object(
            hook, "_git_ignored", return_value=False
        ):
            return hook.shell_authors_visible_files(command, str(self.root))

    def test_normal_commands_are_allowed(self):
        for command in ("cargo test", "cargo fmt", "go test ./...", "./generate.sh"):
            self.assertFalse(self.classify(command), command)

    def test_direct_authoring_is_denied(self):
        for command in (
            "sed -i '' 's/a/b/' src/main.rs",
            "tee src/main.rs",
            "echo hello > src/main.rs",
            "rm src/main.rs",
            "git apply change.patch",
            "python3 -c \"open('src/main.rs','w').write('x')\"",
        ):
            self.assertTrue(self.classify(command), command)

    def test_ignored_output_is_allowed(self):
        with mock.patch.object(hook, "_project_root", return_value=self.root), mock.patch.object(
            hook, "_git_ignored", return_value=True
        ):
            self.assertFalse(hook.shell_authors_visible_files("echo x > target/log", str(self.root)))


class AuthorizationTest(unittest.TestCase):
    def test_authorization_is_one_use(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict(
            os.environ, {"PLUGIN_DATA": directory}
        ):
            hook._record_authorization("session", "patch")
            self.assertTrue(hook._consume_authorization("session", "patch"))
            self.assertFalse(hook._consume_authorization("session", "patch"))

    def test_wrong_patch_does_not_consume_approved_patch(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict(
            os.environ, {"PLUGIN_DATA": directory}
        ):
            hook._record_authorization("session", "approved")
            self.assertFalse(hook._consume_authorization("session", "other"))
            self.assertTrue(hook._consume_authorization("session", "approved"))


class HookModeTest(unittest.TestCase):
    def capture_pre(self, event):
        output = StringIO()
        with redirect_stdout(output):
            hook.handle_pre_tool(event)
        return output.getvalue()

    def test_auto_and_plan_do_not_intercept_patch(self):
        for mode in ("acceptEdits", "dontAsk", "bypassPermissions", "plan"):
            output = self.capture_pre(
                {
                    "permission_mode": mode,
                    "tool_name": "apply_patch",
                    "tool_input": {"command": "*** Begin Patch\n*** End Patch\n"},
                }
            )
            self.assertEqual("", output, mode)

    def test_manual_review_rewrites_pending_patch(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict(
            os.environ, {"PLUGIN_DATA": directory}
        ), mock.patch.object(
            hook,
            "_review_patch",
            return_value=("*** Begin Patch\n*** End Patch\n", "approved"),
        ):
            output = self.capture_pre(
                {
                    "permission_mode": "default",
                    "session_id": "session",
                    "tool_name": "apply_patch",
                    "tool_input": {"command": "original"},
                }
            )
        payload = json.loads(output)
        specific = payload["hookSpecificOutput"]
        self.assertEqual("allow", specific["permissionDecision"])
        self.assertEqual(
            "*** Begin Patch\n*** End Patch\n", specific["updatedInput"]["command"]
        )

    def test_manual_shell_test_is_not_blocked(self):
        output = self.capture_pre(
            {
                "permission_mode": "default",
                "tool_name": "Bash",
                "tool_input": {"command": "cargo test"},
                "cwd": "/tmp",
            }
        )
        self.assertEqual("", output)


class EditorContextTest(unittest.TestCase):
    def capture_user_prompt(self, event):
        output = StringIO()
        with redirect_stdout(output):
            hook.handle_user_prompt(event)
        return output.getvalue()

    def test_formats_file_and_selected_lines(self):
        context = hook.format_editor_context(
            [
                {"path": "src/main.rs"},
                {
                    "path": "src/lib.rs",
                    "startLine": 7,
                    "endLine": 9,
                    "selectedText": "fn selected() {}",
                    "selectionTruncated": False,
                },
            ]
        )
        self.assertIn('"path":"src/main.rs"', context)
        self.assertIn('"startLine":7', context)
        self.assertIn('"endLine":9', context)
        self.assertIn('"selectedText":"fn selected() {}"', context)

    def test_user_prompt_consumes_bridge_context(self):
        endpoint = {"socketPath": "/tmp/bridge.sock", "token": "token"}
        with mock.patch.object(hook, "_project_root", return_value=Path("/repo")), mock.patch.object(
            hook, "_endpoint_candidates", return_value=[(Path("/tmp/endpoint.json"), endpoint)]
        ), mock.patch.object(
            hook,
            "_call_endpoint",
            return_value={
                "attachments": [
                    {
                        "path": "src/main.rs",
                        "startLine": 3,
                        "endLine": 3,
                        "selectedText": "let answer = 42;",
                        "selectionTruncated": False,
                    }
                ]
            },
        ) as call:
            output = self.capture_user_prompt(
                {"session_id": "session", "cwd": "/repo", "prompt": "Explain this"}
            )
        payload = json.loads(output)
        specific = payload["hookSpecificOutput"]
        self.assertEqual("UserPromptSubmit", specific["hookEventName"])
        self.assertIn("src/main.rs", specific["additionalContext"])
        self.assertEqual("takeEditorContext", call.call_args.args[1])

    def test_rejects_unsafe_attachment_paths(self):
        context = hook.format_editor_context(
            [{"path": "/tmp/secret"}, {"path": "../outside"}, {"path": "src/safe.rs"}]
        )
        self.assertNotIn("secret", context)
        self.assertNotIn("outside", context)
        self.assertIn("src/safe.rs", context)

    def test_no_editor_endpoint_does_not_change_prompt(self):
        with mock.patch.object(hook, "_project_root", return_value=Path("/repo")), mock.patch.object(
            hook, "_endpoint_candidates", return_value=[]
        ):
            self.assertEqual(
                "",
                self.capture_user_prompt(
                    {"session_id": "session", "cwd": "/repo", "prompt": "Continue"}
                ),
            )


class PluginConfigurationTest(unittest.TestCase):
    def test_user_prompt_hook_and_shortcut_are_registered(self):
        root = SCRIPT.parents[1]
        hooks = json.loads((root / "hooks" / "hooks.json").read_text(encoding="utf-8"))
        self.assertIn("UserPromptSubmit", hooks["hooks"])

        plugin = ET.parse(
            root / "intellij-plugin" / "src" / "main" / "resources" / "META-INF" / "plugin.xml"
        )
        action = plugin.find("./actions/action[@id='dev.codex.nativeDiff.AttachEditorContext']")
        self.assertIsNotNone(action)
        shortcut = action.find("./keyboard-shortcut")
        self.assertEqual("meta alt shift K", shortcut.attrib["first-keystroke"])


if __name__ == "__main__":
    unittest.main()
