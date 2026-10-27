package dev.silentreview.idea;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

public final class ReviewCommitAction extends AnAction implements DumbAware {
    @Override public void actionPerformed(@NotNull AnActionEvent event){if(event.getProject()!=null)ReviewService.get(event.getProject()).manual(null);}
    @Override public void update(@NotNull AnActionEvent event){event.getPresentation().setEnabled(event.getProject()!=null);}
    @Override public @NotNull ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
}
