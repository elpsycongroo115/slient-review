package dev.silentreview.idea;

import com.intellij.openapi.project.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

public final class ReviewStartupActivity implements StartupActivity,DumbAware {
    @Override public void runActivity(@NotNull Project project){ApplicationManager.getApplication().invokeLater(()->{if(!project.isDisposed())ReviewService.get(project).startFish();});}
}
