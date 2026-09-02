package dev.codex.nativediff;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.editor.ChainDiffVirtualFile;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.diff.util.Side;
import com.intellij.mcpserver.toolsets.general.AddPatchOperation;
import com.intellij.mcpserver.toolsets.general.DeletePatchOperation;
import com.intellij.mcpserver.toolsets.general.PatchApplyEngine;
import com.intellij.mcpserver.toolsets.general.PatchOperation;
import com.intellij.mcpserver.toolsets.general.UpdatePatchOperation;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;

final class PatchReviewService {
    private static final long MAX_TEXT_FILE_BYTES = 10L * 1024L * 1024L;
    private static final Duration SESSION_MAX_AGE = Duration.ofHours(24);

    private final Map<String, ReviewSession> sessions = new ConcurrentHashMap<>();

    BridgeModels.ReviewResult review(Project project, Path root, String patch) throws Exception {
        cleanupSessions();
        Path canonicalRoot = root.toRealPath();
        List<PatchOperation> operations = PatchApplyEngine.INSTANCE.parsePatch(patch);
        if (operations.isEmpty()) {
            throw new IOException("Patch contains no operations");
        }

        Map<String, ContentState> overlay = new LinkedHashMap<>();
        Map<String, ContentState> diskSnapshots = new LinkedHashMap<>();
        Set<String> touched = new HashSet<>();
        List<BridgeModels.AcceptedOperation> accepted = new ArrayList<>();
        String rejectedPath = null;

        for (PatchOperation operation : operations) {
            PreparedOperation prepared = prepareOperation(
                    project, canonicalRoot, operation, overlay, diskSnapshots);
            String editedText = prepared.proposedText;
            if (prepared.reviewInUi) {
                BridgeModels.ReviewDecision decision = showReview(project, prepared);
                if (!decision.approved) {
                    rejectedPath = prepared.relativePath;
                    break;
                }
                editedText = decision.editedText;
            }

            String effectiveKind = prepared.kind;
            if ("delete".equals(effectiveKind) && !editedText.isEmpty()) {
                effectiveKind = "update";
            }
            if ("update".equals(effectiveKind)
                    && prepared.moveTo == null
                    && prepared.baseText.equals(editedText)) {
                continue;
            }

            applyToOverlay(prepared, editedText, overlay, touched);
            accepted.add(new BridgeModels.AcceptedOperation(
                    effectiveKind,
                    prepared.relativePath,
                    prepared.moveTo,
                    prepared.baseText,
                    editedText,
                    hashState(new ContentState(true, prepared.baseText))));
        }

        String decision = rejectedPath == null ? "approved" : (accepted.isEmpty() ? "rejected" : "partial");
        String token = null;
        if (!accepted.isEmpty()) {
            token = UUID.randomUUID().toString();
            Map<String, ContentState> expected = new LinkedHashMap<>();
            for (String path : touched) {
                expected.put(path, overlay.get(path));
            }
            sessions.put(token, new ReviewSession(
                    project,
                    canonicalRoot,
                    Instant.now(),
                    copyStates(diskSnapshots),
                    copyStates(expected),
                    Set.copyOf(touched)));
        }
        String reason = rejectedPath == null ? null : "The patch was rejected in IntelliJ";
        return new BridgeModels.ReviewResult(decision, token, accepted, rejectedPath, reason);
    }

    BridgeModels.ValidationResult validate(String token, String projectRoot, String rewrittenPatch) {
        ReviewSession session = sessions.remove(token);
        if (session == null) {
            return BridgeModels.ValidationResult.error("Review token is missing, expired, or already used");
        }
        try {
            if (session.project.isDisposed()) {
                throw new IOException("The IntelliJ project closed during review");
            }
            if (!session.root.equals(Path.of(projectRoot).toRealPath())) {
                throw new IOException("Review token belongs to a different project");
            }
            for (Map.Entry<String, ContentState> entry : session.diskSnapshots.entrySet()) {
                Path path = PathPolicy.resolveSafe(session.root, entry.getKey());
                ContentState current = readDiskState(session.project, path);
                if (!current.equals(entry.getValue())) {
                    throw new IOException("File changed during review: " + entry.getKey());
                }
            }

            Simulation simulation = simulate(session.project, session.root, rewrittenPatch);
            if (!simulation.touched.equals(session.touched)) {
                throw new IOException("Rewritten patch changes a different set of paths");
            }
            for (String path : session.touched) {
                if (!Objects.equals(simulation.overlay.get(path), session.expectedFinal.get(path))) {
                    throw new IOException("Rewritten patch does not reproduce the reviewed content for " + path);
                }
            }
            return BridgeModels.ValidationResult.ok();
        } catch (Exception exception) {
            String message = exception.getMessage();
            return BridgeModels.ValidationResult.error(
                    message == null ? exception.getClass().getSimpleName() : message);
        }
    }

    private PreparedOperation prepareOperation(
            Project project,
            Path root,
            PatchOperation operation,
            Map<String, ContentState> overlay,
            Map<String, ContentState> snapshots)
            throws Exception {
        Path source = PathPolicy.resolveSafe(root, operation.getPath());
        String relative = PathPolicy.relativeUnix(root, source);
        ContentState base = loadState(project, root, relative, overlay, snapshots);

        String kind;
        String proposed;
        String moveTo = null;
        if (operation instanceof AddPatchOperation add) {
            if (base.exists) {
                throw new IOException("Cannot add an existing file: " + relative);
            }
            kind = "add";
            proposed = add.getContent();
        } else if (operation instanceof DeletePatchOperation) {
            requireExisting(base, relative);
            kind = "delete";
            proposed = "";
        } else if (operation instanceof UpdatePatchOperation update) {
            requireExisting(base, relative);
            kind = update.getMoveTo() == null ? "update" : "move";
            proposed = PatchApplyEngine.INSTANCE.applyHunks(base.text, update.getHunks());
            if (update.getMoveTo() != null) {
                Path destination = PathPolicy.resolveSafe(root, update.getMoveTo());
                moveTo = PathPolicy.relativeUnix(root, destination);
                ContentState destinationState = loadState(project, root, moveTo, overlay, snapshots);
                if (!moveTo.equals(relative) && destinationState.exists) {
                    throw new IOException("Move destination already exists: " + moveTo);
                }
            }
        } else {
            throw new IOException("Unsupported patch operation: " + operation.getClass().getSimpleName());
        }

        boolean review = !isIgnored(root, relative) || (moveTo != null && !isIgnored(root, moveTo));
        return new PreparedOperation(kind, relative, moveTo, base.text, proposed, review);
    }

    private Simulation simulate(Project project, Path root, String patch) throws Exception {
        List<PatchOperation> operations = PatchApplyEngine.INSTANCE.parsePatch(patch);
        Map<String, ContentState> overlay = new LinkedHashMap<>();
        Map<String, ContentState> snapshots = new LinkedHashMap<>();
        Set<String> touched = new HashSet<>();
        for (PatchOperation operation : operations) {
            PreparedOperation prepared = prepareOperation(project, root, operation, overlay, snapshots);
            applyToOverlay(prepared, prepared.proposedText, overlay, touched);
        }
        return new Simulation(overlay, touched);
    }

    private static void applyToOverlay(
            PreparedOperation operation,
            String editedText,
            Map<String, ContentState> overlay,
            Set<String> touched) {
        switch (operation.kind) {
            case "add", "update" -> {
                overlay.put(operation.relativePath, new ContentState(true, editedText));
                touched.add(operation.relativePath);
            }
            case "delete" -> {
                overlay.put(
                        operation.relativePath,
                        editedText.isEmpty()
                                ? new ContentState(false, "")
                                : new ContentState(true, editedText));
                touched.add(operation.relativePath);
            }
            case "move" -> {
                overlay.put(operation.relativePath, new ContentState(false, ""));
                overlay.put(operation.moveTo, new ContentState(true, editedText));
                touched.add(operation.relativePath);
                touched.add(operation.moveTo);
            }
            default -> throw new IllegalStateException("Unknown operation kind: " + operation.kind);
        }
    }

    private static ContentState loadState(
            Project project,
            Path root,
            String relative,
            Map<String, ContentState> overlay,
            Map<String, ContentState> snapshots)
            throws IOException {
        ContentState overlaid = overlay.get(relative);
        if (overlaid != null) {
            return overlaid;
        }
        Path path = PathPolicy.resolveSafe(root, relative);
        ContentState disk = readDiskState(project, path);
        snapshots.putIfAbsent(relative, disk);
        return disk;
    }

    private static ContentState readDiskState(Project project, Path path) throws IOException {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path.toString());
        if (file != null) {
            Document document = FileDocumentManager.getInstance().getCachedDocument(file);
            if (document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
                throw new IOException("Save the affected IntelliJ document before review: " + path);
            }
        }
        if (!Files.exists(path)) {
            return new ContentState(false, "");
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Only regular text files can be reviewed: " + path);
        }
        long size = Files.size(path);
        if (size > MAX_TEXT_FILE_BYTES) {
            throw new IOException("Text file is too large for native review: " + path);
        }
        byte[] bytes = Files.readAllBytes(path);
        for (byte value : bytes) {
            if (value == 0) {
                throw new IOException("Binary files cannot be reviewed in Manual mode: " + path);
            }
        }
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return new ContentState(true, text);
        } catch (CharacterCodingException exception) {
            throw new IOException("Non-UTF-8 files cannot be reviewed in Manual mode: " + path, exception);
        }
    }

    private static void requireExisting(ContentState state, String relative) throws IOException {
        if (!state.exists) {
            throw new IOException("Patch expects an existing file: " + relative);
        }
    }

    private static boolean isIgnored(Path root, String relative) {
        try {
            Process process = new ProcessBuilder(
                            "git", "-C", root.toString(), "check-ignore", "-q", "--", relative)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static BridgeModels.ReviewDecision showReview(Project project, PreparedOperation operation)
            throws InterruptedException, ExecutionException {
        CompletableFuture<BridgeModels.ReviewDecision> future = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                new ReviewUi(project, operation, future).open();
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        });
        return future.get();
    }

    private static final class ReviewUi {
        private final Project project;
        private final PreparedOperation operation;
        private final CompletableFuture<BridgeModels.ReviewDecision> future;
        private final AtomicBoolean completed = new AtomicBoolean();
        private ChainDiffVirtualFile diffFile;
        private FileEditorManagerListener listener;
        private DocumentContent editedContent;
        private final Map<FileEditor, JPanel> reviewBanners = new HashMap<>();

        private ReviewUi(
                Project project,
                PreparedOperation operation,
                CompletableFuture<BridgeModels.ReviewDecision> future) {
            this.project = project;
            this.operation = operation;
            this.future = future;
        }

        private void open() {
            FileType type = FileTypeManager.getInstance().getFileTypeByFileName(
                    operation.moveTo == null ? operation.relativePath : operation.moveTo);
            DocumentContent original = DiffContentFactory.getInstance()
                    .create(project, operation.baseText, type);
            editedContent = DiffContentFactory.getInstance()
                    .createEditable(project, operation.proposedText, type);
            String title = operation.moveTo == null
                    ? "Codex change: " + operation.relativePath
                    : "Codex change: " + operation.relativePath + " → " + operation.moveTo;
            SimpleDiffRequest request = new SimpleDiffRequest(
                    title, original, editedContent, "Current", "Proposed (editable)");
            request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, new boolean[] {true, false});
            request.putUserData(DiffUserDataKeys.PREFERRED_FOCUS_SIDE, Side.RIGHT);

            AnAction applyAction = action("Accept Change", () -> complete(true));
            AnAction rejectAction = action("Reject Change", () -> complete(false));
            request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, List.of(applyAction, rejectAction));

            SimpleDiffRequestChain chain = new SimpleDiffRequestChain(request);
            // Editor-backed diffs collect toolbar actions from the chain context. Request-level
            // user data is only a hint and can be ignored by this presentation path.
            chain.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, List.of(applyAction, rejectAction));
            diffFile = new ChainDiffVirtualFile(chain, title);
            FileEditorManager manager = FileEditorManager.getInstance(project);
            listener = new FileEditorManagerListener() {
                @Override
                public void fileClosed(
                        @NotNull FileEditorManager source, @NotNull VirtualFile file) {
                    if (file == diffFile && !completed.get()) {
                        complete(false);
                    }
                }
            };
            manager.addFileEditorManagerListener(listener);
            FileEditor[] editors = manager.openFile(diffFile, true);
            for (FileEditor editor : editors) {
                JPanel banner = createReviewBanner();
                reviewBanners.put(editor, banner);
                manager.addTopComponent(editor, banner);
            }
            if (editors.length == 0) {
                throw new IllegalStateException("IntelliJ did not open the Codex diff editor");
            }
        }

        private JPanel createReviewBanner() {
            JPanel banner = new JPanel(new BorderLayout(12, 0));
            banner.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 12, 8, 12));

            javax.swing.JLabel instructions = new javax.swing.JLabel(
                    "Codex is waiting. Edit the Proposed side if needed, then accept or reject this change.");
            banner.add(instructions, BorderLayout.CENTER);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            JButton reject = new JButton("Reject Change");
            JButton accept = new JButton("Accept Change");
            reject.addActionListener(ignored -> complete(false));
            accept.addActionListener(ignored -> complete(true));
            buttons.add(reject);
            buttons.add(accept);
            banner.add(buttons, BorderLayout.EAST);
            return banner;
        }

        private AnAction action(String text, Runnable runnable) {
            return new AnAction(text) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent event) {
                    runnable.run();
                }

                @Override
                public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.EDT;
                }
            };
        }

        private void complete(boolean approved) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            String edited = approved && editedContent != null
                    ? editedContent.getDocument().getText()
                    : operation.proposedText;
            FileEditorManager manager = FileEditorManager.getInstance(project);
            if (listener != null) {
                manager.removeFileEditorManagerListener(listener);
            }
            reviewBanners.forEach(manager::removeTopComponent);
            reviewBanners.clear();
            if (diffFile != null && manager.isFileOpen(diffFile)) {
                manager.closeFile(diffFile);
            }
            future.complete(new BridgeModels.ReviewDecision(approved, edited));
        }
    }

    private void cleanupSessions() {
        Instant cutoff = Instant.now().minus(SESSION_MAX_AGE);
        sessions.entrySet().removeIf(entry -> entry.getValue().created.isBefore(cutoff));
    }

    private static Map<String, ContentState> copyStates(Map<String, ContentState> source) {
        Map<String, ContentState> copy = new LinkedHashMap<>();
        source.forEach((path, state) -> copy.put(path, new ContentState(state.exists, state.text)));
        return copy;
    }

    private static String hashState(ContentState state) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) (state.exists ? 1 : 0));
            digest.update(state.text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record ContentState(boolean exists, String text) {}

    private record PreparedOperation(
            String kind,
            String relativePath,
            String moveTo,
            String baseText,
            String proposedText,
            boolean reviewInUi) {}

    private record Simulation(Map<String, ContentState> overlay, Set<String> touched) {}

    private record ReviewSession(
            Project project,
            Path root,
            Instant created,
            Map<String, ContentState> diskSnapshots,
            Map<String, ContentState> expectedFinal,
            Set<String> touched) {}
}
