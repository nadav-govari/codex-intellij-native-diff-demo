package dev.codex.nativediff;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

public final class NativeDiffStartupActivity implements StartupActivity.Background {
    @Override
    public void runActivity(@NotNull Project project) {
        ApplicationManager.getApplication()
                .getService(NativeDiffBridgeService.class)
                .ensureStarted();
    }
}
