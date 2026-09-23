package com.casc.unittestagent.ui.component;

import com.casc.unittestagent.model.StepStatus;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JButton;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * UnitMind 步骤条组件。
 * 使用紧凑按钮模拟 Stepper，支持点击切换、当前高亮和步骤状态展示。
 */
public class UnitMindStepperPanel extends JBPanel<UnitMindStepperPanel> {
    /** 步骤按钮列表。 */
    private final List<JButton> buttons = new ArrayList<>();
    /** 步骤点击回调。 */
    private final IntConsumer onStepSelected;
    /** 完整步骤名。 */
    private final String[] fullSteps;
    /** 窄窗口步骤名。 */
    private final String[] shortSteps;

    /**
     * 创建步骤条。
     *
     * @param steps 步骤名称
     * @param onStepSelected 步骤点击回调
     */
    public UnitMindStepperPanel(String[] steps, IntConsumer onStepSelected) {
        super(new UnitMindWrapLayout(FlowLayout.LEFT, 6, 4));
        this.onStepSelected = onStepSelected;
        this.fullSteps = steps.clone();
        this.shortSteps = shortSteps(steps);
        setBorder(JBUI.Borders.empty(4, 8));
        setBackground(UIUtil.getPanelBackground());
        for (int i = 0; i < steps.length; i++) {
            int index = i;
            JButton button = new JButton(steps[i]);
            button.setFocusable(false);
            button.setToolTipText(tooltip(steps[i]));
            button.addActionListener(event -> this.onStepSelected.accept(index));
            buttons.add(button);
            add(button);
        }
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                revalidate();
                repaint();
            }
        });
    }

    /**
     * 更新步骤条状态。
     *
     * @param currentIndex 当前步骤索引
     * @param statuses 每个步骤状态
     */
    public void update(int currentIndex, StepStatus[] statuses) {
        for (int i = 0; i < buttons.size(); i++) {
            StepStatus status = statuses == null || i >= statuses.length ? StepStatus.NOT_STARTED : statuses[i];
            JButton button = buttons.get(i);
            button.setText(buttonText(i, status, displayName(i)));
            button.setEnabled(status != StepStatus.NOT_STARTED || i == 0 || hasPreviousData(statuses, i));
            button.setBackground(i == currentIndex ? new JBColor(0xDCEBFF, 0x2F3B4D) : UIUtil.getPanelBackground());
        }
        revalidate();
        repaint();
    }

    /**
     * 生成按钮文本。
     */
    private String buttonText(int index, StepStatus status, String name) {
        String prefix = String.valueOf(index + 1);
        if (status == StepStatus.SUCCESS) {
            return prefix + " " + name;
        }
        if (status == StepStatus.WARNING) {
            return prefix + " " + name + " !";
        }
        if (status == StepStatus.RUNNING || status == StepStatus.FAILED) {
            return prefix + " " + name + " " + status.getDisplayText();
        }
        return prefix + " " + name;
    }

    private String displayName(int index) {
        return getWidth() > 520 || index >= shortSteps.length ? fullSteps[index] : shortSteps[index];
    }

    private String[] shortSteps(String[] steps) {
        String[] result = new String[steps.length];
        for (int i = 0; i < steps.length; i++) {
            result[i] = steps[i]
                    .replace("静态分析结果", "静态分析")
                    .replace("测试场景", "场景")
                    .replace("Mock策略", "Mock")
                    .replace("测试代码", "代码")
                    .replace("写入测试文件", "写入");
        }
        return result;
    }

    private String tooltip(String step) {
        switch (step) {
            case "静态分析结果":
                return "静态分析结果：展示方法信息、参数信息、参数约束、控制流分支和依赖调用";
            case "测试场景":
                return "测试场景：正常/异常/边界场景及触发条件，可直接编辑，编辑后旧测试代码会失效";
            case "Mock策略":
                return "Mock 策略：静态策略与可选大模型候选策略，可选择或编辑，变化后旧测试代码会失效";
            case "测试代码":
                return "测试代码：生成、编辑、编译校验与覆盖率闭环";
            case "写入测试文件":
                return "写入测试文件：选择写入模式，编译与执行门禁通过后写入目标测试类";
            default:
                return step;
        }
    }

    /**
     * 判断前置步骤是否已有数据。
     */
    private boolean hasPreviousData(StepStatus[] statuses, int index) {
        if (statuses == null) {
            return false;
        }
        for (int i = 0; i < index; i++) {
            if (statuses[i] == StepStatus.SUCCESS || statuses[i] == StepStatus.WARNING) {
                return true;
            }
        }
        return false;
    }
}
