package com.casc.unittestagent.ui.step;

import com.casc.unittestagent.model.BranchModel;
import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.model.DependencyModel;
import com.casc.unittestagent.model.GenerationResult;
import com.casc.unittestagent.model.MethodModel;
import com.intellij.testFramework.LightPlatformTestCase;

import javax.swing.JButton;
import javax.swing.text.JTextComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class StaticAnalysisStepPanelTest extends LightPlatformTestCase {
    public void testShowsConcreteReasonWhenClassAnalysisIsUnsupported() {
        ClassGenerationResult result = new ClassGenerationResult();
        result.getWarnings().add("整类生成首期不支持抽象类。");

        StaticAnalysisStepPanel panel = new StaticAnalysisStepPanel(result, "/sample/AbstractService.java");

        assertTrue(componentContainsText(panel, "整类静态分析未通过"));
        assertTrue(componentContainsText(panel, "不支持抽象类"));
    }

    public void testPendingStateOffersAnalyzeAction() {
        AtomicInteger analyzeCalls = new AtomicInteger();

        StaticAnalysisStepPanel panel = new StaticAnalysisStepPanel(
                null, "", null, analyzeCalls::incrementAndGet);

        assertTrue(componentContainsText(panel, "尚未分析方法"));
        JButton analyze = findButton(panel, "分析当前方法");
        assertNotNull(analyze);
        analyze.doClick();
        assertEquals(1, analyzeCalls.get());
    }

    public void testFailedAnalysisShowsReasonAndRetryAction() {
        GenerationResult result = new GenerationResult();
        result.getWarnings().add("光标不在任何 Java 方法内。");
        AtomicInteger analyzeCalls = new AtomicInteger();

        StaticAnalysisStepPanel panel = new StaticAnalysisStepPanel(
                result, "/sample/Sample.java", "光标不在任何 Java 方法内。", analyzeCalls::incrementAndGet);

        assertTrue(componentContainsText(panel, "静态分析未通过"));
        assertTrue(componentContainsText(panel, "光标不在任何 Java 方法内"));
        JButton retry = findButton(panel, "重新分析");
        assertNotNull(retry);
        retry.doClick();
        assertEquals(1, analyzeCalls.get());
    }

    public void testMethodAnalysisShowsControlFlowBranchesAndDependencies() {
        MethodModel method = new MethodModel();
        method.setClassName("SampleService");
        method.setMethodName("submit");
        method.setReturnType("boolean");
        method.setSourceStartLine(12);
        method.setSourceEndLine(30);
        method.getThrownExceptions().add("java.io.IOException");
        BranchModel branch = new BranchModel();
        branch.setSourceStartLine(14);
        branch.setSourceEndLine(16);
        branch.setCondition("request == null");
        branch.setAction("throw new IllegalArgumentException");
        branch.setExceptionType("java.lang.IllegalArgumentException");
        method.getBranches().add(branch);
        DependencyModel dependency = new DependencyModel();
        dependency.setFieldName("orderRepository");
        dependency.setFieldType("OrderRepository");
        dependency.setDependencyType("数据库");
        dependency.setCalledMethods(List.of("save", "findByKey"));
        dependency.setCallExpressions(List.of("orderRepository.save(request)"));
        method.getDependencies().add(dependency);
        GenerationResult result = new GenerationResult();
        result.setMethodModel(method);

        StaticAnalysisStepPanel panel = new StaticAnalysisStepPanel(result, "/sample/SampleService.java");

        assertTrue(componentContainsText(panel, "控制流分支"));
        assertTrue(componentContainsText(panel, "14 - 16"));
        assertTrue(componentContainsText(panel, "request == null"));
        assertTrue(componentContainsText(panel, "java.lang.IllegalArgumentException"));
        assertTrue(componentContainsText(panel, "依赖调用"));
        assertTrue(componentContainsText(panel, "orderRepository.save(request)"));
        assertTrue(componentContainsText(panel, "save, findByKey"));
        assertTrue(componentContainsText(panel, "数据库"));
        assertTrue(componentContainsText(panel, "字段"));
        assertTrue(componentContainsText(panel, "12 - 30"));
        assertTrue(componentContainsText(panel, "java.io.IOException"));
    }

    public void testMethodWithoutBranchesOrDependenciesShowsPlaceholders() {
        MethodModel method = new MethodModel();
        method.setClassName("SampleService");
        method.setMethodName("ping");
        method.setReturnType("void");
        GenerationResult result = new GenerationResult();
        result.setMethodModel(method);

        StaticAnalysisStepPanel panel = new StaticAnalysisStepPanel(result, "/sample/SampleService.java");

        assertTrue(componentContainsText(panel, "未识别到条件分支"));
        assertTrue(componentContainsText(panel, "未识别到字段或参数依赖调用"));
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

    private boolean componentContainsText(Component component, String expected) {
        if (component instanceof JTextComponent
                && ((JTextComponent) component).getText().contains(expected)) return true;
        if (component instanceof JLabel
                && ((JLabel) component).getText() != null
                && ((JLabel) component).getText().contains(expected)) return true;
        if (component instanceof JTable) {
            JTable table = (JTable) component;
            for (int row = 0; row < table.getRowCount(); row++) {
                for (int column = 0; column < table.getColumnCount(); column++) {
                    Object value = table.getValueAt(row, column);
                    if (value != null && value.toString().contains(expected)) return true;
                }
            }
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                if (componentContainsText(child, expected)) return true;
            }
        }
        return false;
    }
}
