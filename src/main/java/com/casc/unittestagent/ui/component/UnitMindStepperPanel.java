package com.casc.unittestagent.ui.component;

import com.casc.unittestagent.model.StepStatus;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JButton;
import java.awt.FlowLayout;
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

    /**
     * 创建步骤条。
     *
     * @param steps 步骤名称
     * @param onStepSelected 步骤点击回调
     */
    public UnitMindStepperPanel(String[] steps, IntConsumer onStepSelected) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 4));
        this.onStepSelected = onStepSelected;
        setBorder(JBUI.Borders.empty(4, 8));
        setBackground(UIUtil.getPanelBackground());
        for (int i = 0; i < steps.length; i++) {
            int index = i;
            JButton button = new JButton(steps[i]);
            button.setFocusable(false);
            button.addActionListener(event -> this.onStepSelected.accept(index));
            buttons.add(button);
            add(button);
        }
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
            button.setText(buttonText(i, status));
            button.setEnabled(status != StepStatus.NOT_STARTED || i == 0 || hasPreviousData(statuses, i));
            button.setBackground(i == currentIndex ? new JBColor(0xDCEBFF, 0x2F3B4D) : UIUtil.getPanelBackground());
        }
    }

    /**
     * 生成按钮文本。
     */
    private String buttonText(int index, StepStatus status) {
        String prefix = String.valueOf(index + 1);
        if (status == StepStatus.SUCCESS) {
            return prefix + " ✓ " + trimNumber(buttons.get(index).getText());
        }
        if (status == StepStatus.RUNNING || status == StepStatus.WARNING || status == StepStatus.FAILED) {
            return prefix + " " + trimNumber(buttons.get(index).getText()) + " " + status.getDisplayText();
        }
        return prefix + " " + trimNumber(buttons.get(index).getText());
    }

    /**
     * 去除旧按钮文本中的编号和状态。
     */
    private String trimNumber(String text) {
        String value = text == null ? "" : text;
        value = value.replaceAll("^\\d+\\s*(✓\\s*)?", "");
        value = value.replace("进行中", "").replace("有警告", "").replace("失败", "").trim();
        return value;
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
