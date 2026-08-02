package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.GenerationStatus;
import com.intellij.testFramework.LightPlatformTestCase;

import javax.swing.JButton;
import java.awt.Component;
import java.awt.Container;

public class TestCodeStepPanelTest extends LightPlatformTestCase {
    public void testRestoreUsesOriginalGeneratedCodeAfterPanelRebuild() {
        String generated = "class SubjectTest { /* generated */ }";
        String edited = "class SubjectTest { /* edited */ }";
        TestCodeStepPanel panel = panel(generated, edited);

        assertEquals(edited, panel.getCode());
        JButton restore = findButton(panel, "恢复生成代码");
        assertNotNull(restore);

        restore.doClick();

        assertEquals(generated, panel.getCode());
    }

    public void testLatestRegenerationBecomesNewRestoreBaseline() {
        String regenerated = "class SubjectTest { /* regenerated */ }";
        TestCodeStepPanel panel = panel(regenerated, "class SubjectTest { /* manual edit */ }");

        JButton restore = findButton(panel, "恢复生成代码");
        assertNotNull(restore);
        restore.doClick();

        assertEquals(regenerated, panel.getCode());
    }

    private TestCodeStepPanel panel(String generatedCode, String currentCode) {
        return new TestCodeStepPanel(
                getProject(),
                GenerationStatus.SUCCESS,
                generatedCode,
                currentCode,
                "",
                "",
                null,
                feedback -> { },
                () -> { },
                code -> { });
    }

    private JButton findButton(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton && text.equals(((JButton) component).getText())) {
                return (JButton) component;
            }
            if (component instanceof Container) {
                JButton found = findButton((Container) component, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
