package com.casc.unittestagent.ui;

import com.casc.unittestagent.writer.TestWriteMode;
import com.casc.unittestagent.writer.WriteModeSelector;
import com.intellij.openapi.project.Project;
import java.io.File;

public final class SwingWriteModeSelector implements WriteModeSelector {
    @Override
    public Choice select(Project project, File target, boolean hasExistingRegion) {
        var dialog = new TestWriteModeDialog(project, target, hasExistingRegion);
        return dialog.showAndGet() ? new Choice(dialog.getSelectedMode(), dialog.isReplaceExistingRegion())
                : new Choice(TestWriteMode.CANCEL, true);
    }
}
