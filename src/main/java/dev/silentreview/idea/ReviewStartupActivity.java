package dev.silentreview.idea;

import com.intellij.openapi.project.*;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;
import javax.swing.SwingUtilities;

public final class ReviewStartupActivity implements StartupActivity,DumbAware {
    @Override public void runActivity(@NotNull Project project){SwingUtilities.invokeLater(()->{if(!project.isDisposed())ReviewService.get(project).startFish();});}
}
