package dev.codex.nativediff;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

/** Attaches the active editor file or selection to the next Codex prompt. */
public final class AttachEditorContextAction extends DumbAwareAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file == null) {
            HintManager.getInstance().showErrorHint(editor, "The active editor is not a local file");
            return;
        }

        Selection selection = selection(editor);
        try {
            BridgeModels.EditorContextAttachment attachment = ApplicationManager.getApplication()
                    .getService(NativeDiffBridgeService.class)
                    .queueEditorContext(
                            project,
                            file,
                            selection.startLine,
                            selection.endLine,
                            selection.text);
            HintManager.getInstance().showInformationHint(
                    editor, "Attached " + attachment.displayReference() + " to the next Codex prompt");
        } catch (IOException exception) {
            HintManager.getInstance().showErrorHint(
                    editor, "Could not attach editor context: " + exception.getMessage());
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        VirtualFile file = editor == null
                ? null
                : FileDocumentManager.getInstance().getFile(editor.getDocument());
        event.getPresentation().setEnabledAndVisible(
                project != null && editor != null && file != null && file.isInLocalFileSystem());
    }

    private static Selection selection(Editor editor) {
        SelectionModel model = editor.getSelectionModel();
        if (!model.hasSelection()) {
            return new Selection(null, null, null);
        }
        Document document = editor.getDocument();
        int startOffset = model.getSelectionStart();
        int endOffset = model.getSelectionEnd();
        int inclusiveEndOffset = Math.max(startOffset, endOffset - 1);
        return new Selection(
                document.getLineNumber(startOffset) + 1,
                document.getLineNumber(inclusiveEndOffset) + 1,
                model.getSelectedText());
    }

    private record Selection(Integer startLine, Integer endLine, String text) {}
}
