package dev.codex.nativediff;

import java.util.List;

final class BridgeModels {
    private BridgeModels() {}

    static final class AcceptedOperation {
        final String kind;
        final String path;
        final String moveTo;
        final String baseText;
        final String editedText;
        final String baseSha256;

        AcceptedOperation(
                String kind,
                String path,
                String moveTo,
                String baseText,
                String editedText,
                String baseSha256) {
            this.kind = kind;
            this.path = path;
            this.moveTo = moveTo;
            this.baseText = baseText;
            this.editedText = editedText;
            this.baseSha256 = baseSha256;
        }
    }

    static final class ReviewResult {
        final String decision;
        final String reviewToken;
        final List<AcceptedOperation> acceptedOperations;
        final String rejectedPath;
        final String reason;

        ReviewResult(
                String decision,
                String reviewToken,
                List<AcceptedOperation> acceptedOperations,
                String rejectedPath,
                String reason) {
            this.decision = decision;
            this.reviewToken = reviewToken;
            this.acceptedOperations = acceptedOperations;
            this.rejectedPath = rejectedPath;
            this.reason = reason;
        }
    }

    static final class ValidationResult {
        final boolean ok;
        final String reason;

        ValidationResult(boolean ok, String reason) {
            this.ok = ok;
            this.reason = reason;
        }

        static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        static ValidationResult error(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    static final class ReviewDecision {
        final boolean approved;
        final String editedText;

        ReviewDecision(boolean approved, String editedText) {
            this.approved = approved;
            this.editedText = editedText;
        }
    }

    static final class EditorContextAttachment {
        final String path;
        final Integer startLine;
        final Integer endLine;
        final String selectedText;
        final boolean selectionTruncated;
        final long attachedAtEpochMillis;

        EditorContextAttachment(
                String path,
                Integer startLine,
                Integer endLine,
                String selectedText,
                boolean selectionTruncated,
                long attachedAtEpochMillis) {
            this.path = path;
            this.startLine = startLine;
            this.endLine = endLine;
            this.selectedText = selectedText;
            this.selectionTruncated = selectionTruncated;
            this.attachedAtEpochMillis = attachedAtEpochMillis;
        }

        String displayReference() {
            if (startLine == null || endLine == null) {
                return path;
            }
            return startLine.equals(endLine)
                    ? path + ":" + startLine
                    : path + ":" + startLine + "-" + endLine;
        }
    }

    static final class EditorContextResult {
        final List<EditorContextAttachment> attachments;

        EditorContextResult(List<EditorContextAttachment> attachments) {
            this.attachments = attachments;
        }
    }
}
