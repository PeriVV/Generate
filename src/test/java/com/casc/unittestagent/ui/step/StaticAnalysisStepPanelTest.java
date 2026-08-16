package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.ClassGenerationResult;
import com.intellij.testFramework.LightPlatformTestCase;

import javax.swing.text.JTextComponent;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;

public class StaticAnalysisStepPanelTest extends LightPlatformTestCase {
    public void testShowsConcreteReasonWhenClassAnalysisIsUnsupported() {
        ClassGenerationResult result = new ClassGenerationResult();
        result.getWarnings().add("整类生成首期不支持抽象类。");

        StaticAnalysisStepPanel panel = new StaticAnalysisStepPanel(result, "/sample/AbstractService.java");

        assertTrue(componentContainsText(panel, "整类静态分析未通过"));
        assertTrue(componentContainsText(panel, "不支持抽象类"));
    }

    private boolean componentContainsText(Component component, String expected) {
        if (component instanceof JTextComponent
                && ((JTextComponent) component).getText().contains(expected)) return true;
        if (component instanceof JLabel
                && ((JLabel) component).getText() != null
                && ((JLabel) component).getText().contains(expected)) return true;
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                if (componentContainsText(child, expected)) return true;
            }
        }
        return false;
    }
}
