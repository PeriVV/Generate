package com.casc.unittestagent.writer;

import com.intellij.openapi.project.Project;
import java.io.File;

/** UI supplies a decision; the writer never opens Swing dialogs. */
@FunctionalInterface
public interface WriteModeSelector {
    Choice select(Project project, File target, boolean hasExistingRegion);
    record Choice(TestWriteMode mode, boolean replaceExistingRegion) { }
}
