package com.casc.unittestagent.ui;

import com.casc.unittestagent.writer.TestWriteMode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.io.File;

/**
 * 测试文件已存在时的写入方式选择对话框。
 * 默认选择追加，避免用户无意覆盖已有测试类。
 */
public class TestWriteModeDialog extends DialogWrapper {
    /** 追加到已有测试类的选项，默认选中以保护历史用例。 */
    private final JRadioButton appendButton = new JRadioButton("追加到已有测试类", true);
    /** 覆盖整个测试类的选项，用户明确选择后才会执行。 */
    private final JRadioButton overwriteButton = new JRadioButton("覆盖整个测试类");
    /** 取消写入的选项，用于放弃当前操作。 */
    private final JRadioButton cancelButton = new JRadioButton("取消");
    /** 同一方法已存在 UnitMind 区域时是否替换旧区域。 */
    private final JCheckBox replaceRegionBox = new JCheckBox("如果当前方法已存在 UnitMind 生成区域，则替换旧区域", true);
    /** 已存在的目标测试文件。 */
    private final File targetFile;
    /** 是否检测到同一方法的 UnitMind region。 */
    private final boolean hasExistingRegion;

    /**
     * 创建写入方式选择对话框。
     *
     * @param project 当前 IntelliJ 项目上下文
     * @param targetFile 已存在的目标测试文件
     * @param hasExistingRegion 是否已存在同一被测方法的 UnitMind 生成区域
     */
    public TestWriteModeDialog(@Nullable Project project, File targetFile, boolean hasExistingRegion) {
        super(project);
        this.targetFile = targetFile;
        this.hasExistingRegion = hasExistingRegion;
        setTitle("UnitMind 写入测试文件");
        setOKButtonText("确定");
        setCancelButtonText("关闭");
        init();
    }

    /**
     * 创建对话框中心内容。
     *
     * @return 写入模式选择组件
     */
    @Override
    protected @Nullable JComponent createCenterPanel() {
        ButtonGroup group = new ButtonGroup();
        group.add(appendButton);
        group.add(overwriteButton);
        group.add(cancelButton);

        JPanel options = new JPanel(new GridLayout(0, 1, 0, 8));
        options.add(new JLabel("目标测试类已存在："));
        options.add(new JLabel(targetFile == null ? "" : targetFile.getAbsolutePath()));
        options.add(new JLabel("请选择写入方式："));
        options.add(appendButton);
        options.add(new JLabel("追加到已有测试类：保留原有测试方法，只追加当前方法的新测试。"));
        options.add(overwriteButton);
        options.add(new JLabel("覆盖整个测试类：删除原有内容，写入本次生成内容。"));
        options.add(cancelButton);
        if (hasExistingRegion) {
            options.add(replaceRegionBox);
        }

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        panel.add(options, BorderLayout.CENTER);
        return panel;
    }

    /**
     * 获取用户选择的写入模式。
     *
     * @return 写入模式，取消时返回 CANCEL
     */
    public TestWriteMode getSelectedMode() {
        if (cancelButton.isSelected()) {
            return TestWriteMode.CANCEL;
        }
        if (overwriteButton.isSelected()) {
            return TestWriteMode.OVERWRITE_EXISTING;
        }
        return TestWriteMode.APPEND_TO_EXISTING;
    }

    /**
     * 判断同一方法旧区域是否需要替换。
     *
     * @return 需要替换旧区域时返回 true
     */
    public boolean isReplaceExistingRegion() {
        return !hasExistingRegion || replaceRegionBox.isSelected();
    }
}
