# Codex IntelliJ Native Diff

This reference implementation gives Codex CLI a Claude Code-style IntelliJ review flow. Codex
still proposes normal `apply_patch` operations, but in Manual mode each
VCS-visible text change opens in IntelliJ's native diff editor. The proposed
side is editable and nothing reaches the working tree until you choose Accept.

For an implementation map, protocol overview, and the Codex integration gaps
this project exposes, see [OpenAI handoff](docs/openai-handoff.md).

The implementation has two parts:

- A Codex personal plugin with deterministic lifecycle hooks.
- An IntelliJ IDEA plugin with the native diff UI and a private local bridge.

The bridge is not an MCP server and is never selected by the model. The hook
intercepts the pending patch itself, which is what makes the workflow reliable.

## Requirements

- macOS
- Codex CLI with lifecycle hooks (tested with 0.147.x and 0.149.1)
- IntelliJ IDEA Ultimate 2026.2 (`IU-262`)
- A Git working tree opened as an IntelliJ project
- Python 3.9 or newer

## Build

```sh
./scripts/build-and-check.sh
```

The IntelliJ ZIP is produced at:

```text
intellij-plugin/build/distributions/codex-intellij-native-diff-0.2.0.zip
```

Install it with **Settings → Plugins → gear menu → Install Plugin from Disk**,
then restart IntelliJ. Install the Codex personal plugin from the `personal`
marketplace and start a new Codex session. Run `/hooks` once to review and trust
the bundled hook definition.

### Current Code Mode workaround

Codex CLI 0.149.1 does not emit `PreToolUse` for `apply_patch` calls nested under
Code Mode's JavaScript `exec` tool. Until that upstream gap is fixed, start only
the Codex process that should use native diffs through the included launcher:

```sh
./scripts/codex-native-diff
```

To resume an existing conversation through the hook-compatible direct-tool path:

```sh
./scripts/codex-native-diff resume
```

This does not disable coding capabilities. It disables the JavaScript tool
orchestrator for that process, so Codex invokes `apply_patch` directly and the
hook can review it. It does not change global Codex configuration or other
running sessions.

## Use

Run `/permissions` in Codex CLI:

- **Auto** keeps the normal direct-editing behavior.
- **Read Only** enables Manual native-diff review.
- **Plan** remains planning-only and is not treated as Manual mode.

The mode is read from every hook event, so changing it takes effect on the next
tool call. The Code Mode workaround above is currently required for patch hook
events to be delivered at all.

In Manual mode:

1. Codex calls `apply_patch`.
2. IntelliJ opens one native diff tab per file, in patch order.
3. Edit the right-hand proposal if desired.
4. Use the persistent banner above the diff to choose **Accept Change** or
   **Reject Change**. The same actions are also available in the diff toolbar.
5. Accept advances to the next file. Reject or closing the tab aborts the rest.
6. If a later file is rejected, the already accepted prefix is still applied.
7. The bridge reparses the edited patch and compares its final content to the
   reviewed proposal before Codex receives permission to write it.

Tests, builds, formatters, and generators remain trusted and run normally.
Obvious shell-based source authoring (`sed -i`, project-file redirection, `tee`,
`git apply`, and similar commands) is blocked and routed back to `apply_patch`.

### Attach editor context

With a normal file editor focused, press **⌘⌥⇧K** or choose **Attach Editor
Context to Codex** from the editor context menu:

- With no selection, the project-relative file path is attached.
- With selected text, the exact 1-based line range and selected text are attached.
- Press the shortcut in multiple files to queue multiple references.
- The attachments are consumed once by the next Codex prompt in that project.

The shortcut is configurable under **Settings → Keymap → Attach Editor Context
to Codex**. IntelliJ shows a confirmation hint after each attachment. Pending
attachments expire after ten minutes; selected text is capped at 32 KiB.

## Safety and limits

- Existing dirty working-tree changes are used as the patch baseline and are
  never cleaned, staged, or committed.
- Save affected IntelliJ documents before review; unsaved documents fail closed.
- Ignored build/cache files are accepted without opening a diff.
- Binary, non-UTF-8, out-of-project, `.git`, and symlink-escape targets fail closed.
- Additions without a final newline may not be representable by Codex's patch
  grammar; the round-trip validator rejects them without changing the file.
- Hooks are a workflow guardrail, not an operating-system security boundary.
  The plugin covers Codex's built-in patch/shell paths and the IDEA 2026.2 MCP
  mutators.

## Local bridge

Each IntelliJ process owns an authenticated Unix socket and a mode-`0600`
discovery record under:

```text
~/Library/Caches/CodexNativeDiff/v1/endpoints/
```

The bridge only supports `reviewPatch`, `validatePatch`, and the one-shot
`takeEditorContext` operation. It cannot execute commands or directly write
project files. Endpoint records and sockets are removed when the plugin stops,
and stale process records are cleaned at startup.

## Development checks

```sh
python3 -m unittest discover -s tests -v
cd intellij-plugin
JAVA_HOME='/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home' ./gradlew test buildPlugin
```

The Java compatibility tests exercise JetBrains' public `PatchApplyEngine` as a
black box. No third-party plugin implementation is copied or decompiled.
