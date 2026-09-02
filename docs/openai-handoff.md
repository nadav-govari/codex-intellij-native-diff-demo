# OpenAI engineering handoff

This repository is a working reference implementation of native, editable patch
review for Codex CLI inside IntelliJ IDEA. It was built to reproduce the review
flow available in Claude Code: the agent proposes a patch, the IDE displays it
in its native diff UI, the user may edit the proposed side, and the working tree
changes only after an explicit acceptance.

## What is implemented

- Editable IntelliJ native diff tabs, one per file and in patch order.
- Explicit **Accept Change** and **Reject Change** actions in a persistent banner
  and the diff toolbar.
- Partial acceptance: accepting an earlier file and rejecting a later file
  applies only the accepted prefix.
- Round-trip validation of user-edited proposals before the original Codex
  patch tool is authorized.
- A project-scoped, authenticated Unix-socket bridge that cannot execute shell
  commands or directly mutate project files.
- A Codex `UserPromptSubmit` hook plus IntelliJ action for attaching the active
  file or exact selected line range to the next prompt.
- Guardrails for dirty trees, unsaved documents, ignored output, binary files,
  path traversal, `.git`, symlink escapes, shell-based authoring, and stale or
  replayed approvals.

## Components

| Component | Location | Responsibility |
| --- | --- | --- |
| Codex plugin manifest | `.codex-plugin/plugin.json` | Plugin identity and UI metadata |
| Codex lifecycle hooks | `hooks/hooks.json` | Registers session, prompt, pre-tool, permission, and post-tool events |
| Hook implementation | `scripts/native_diff_hook.py` | Classifies tools, sends patches to IntelliJ, rewrites approved tool input, and validates one-use authorization |
| IntelliJ bridge | `intellij-plugin/src/main/java/dev/codex/nativediff/NativeDiffBridgeService.java` | Publishes authenticated local endpoints and serves the narrow bridge protocol |
| Review UI and simulation | `intellij-plugin/src/main/java/dev/codex/nativediff/PatchReviewService.java` | Applies a patch to in-memory content, opens native editable diffs, and validates the result |
| Editor attachment action | `intellij-plugin/src/main/java/dev/codex/nativediff/AttachEditorContextAction.java` | Queues the focused file or selected lines for the next Codex prompt |
| Tests | `tests/` and `intellij-plugin/src/test/` | Covers hook behavior, path policy, protocol behavior, and JetBrains patch compatibility |

## Patch review sequence

1. Codex proposes an `apply_patch` tool call.
2. The `PreToolUse` hook receives the still-pending patch.
3. The hook locates the IntelliJ endpoint for the current Git root and sends
   `reviewPatch` over an authenticated Unix socket.
4. IntelliJ parses and simulates the patch entirely in memory, then opens native
   editable diff tabs.
5. The user edits and accepts or rejects each file.
6. IntelliJ returns the accepted operations and a one-use review token.
7. The hook encodes the reviewed content back into Codex patch grammar and calls
   `validatePatch`.
8. IntelliJ independently reparses the rewritten patch and verifies that its
   result exactly matches what the user reviewed.
9. The hook rewrites the pending tool input and records a short-lived,
   session-scoped authorization hash.
10. `PermissionRequest` consumes that authorization once; Codex's original
    patch tool performs the actual filesystem mutation.

The bridge deliberately does not write files. Codex remains the sole writer,
which keeps sandboxing, approvals, and the normal tool result intact.

## Upstream Codex integration gaps found

### Nested Code Mode tools bypass lifecycle hooks

In Codex CLI 0.149.1, a model can invoke `functions.exec`, whose JavaScript then
calls `tools.apply_patch(...)`. That nested patch does not emit the `PreToolUse`
event required by this integration, so it reaches the filesystem without native
review. The same behavior is tracked in
[openai/codex#23411](https://github.com/openai/codex/issues/23411).

The included `scripts/codex-native-diff` launcher disables only Code Mode and
Code-Mode-only routing for its child process. Codex then exposes direct tools,
and patch hooks work. This is an operational workaround, not the desired native
solution.

A native fix would deliver lifecycle hooks for every nested tool call with the
real nested tool name and input, before execution, regardless of whether the
call originated from direct model output or the JavaScript orchestrator.

### Hook permission metadata is not expressive enough

The hook payload's `permission_mode` currently collapses multiple reviewer
choices into `default`; it cannot reliably distinguish an explicit user review
mode from automatic review. That limitation is tracked in
[openai/codex#23465](https://github.com/openai/codex/issues/23465).

A stable reviewer/approval-policy field on every relevant hook event would let
an integration activate native diffs only for the user's intended mode and
would make mid-session permission changes deterministic.

## Suggested native direction

The cleanest product integration would make a patch proposal a first-class
Codex event rather than requiring a local hook to reinterpret a tool call. An
IDE client could receive a structured multi-file proposal with base content,
display its native editable diff, and return accepted final content or a
rejection. Codex would then apply only that reviewed result through its normal
sandboxed writer.

The reference bridge protocol demonstrates the minimum useful contract:

- protocol version and request ID;
- session ID and canonical project root;
- original patch proposal;
- per-file final content and accept/reject outcome;
- one-use validation token;
- explicit validation of the rewritten patch before filesystem mutation.

The editor-context path likewise benefits from a first-class IDE attachment API:
project-relative path, optional 1-based line range, optional selected text, and
one-shot consumption by the next user prompt.

## Build and verification

Run the complete local verification suite from the repository root:

```sh
./scripts/build-and-check.sh
```

The build uses only public JetBrains APIs. The patch compatibility tests exercise
JetBrains' public `PatchApplyEngine` as a black box; no third-party plugin was
decompiled or copied.
