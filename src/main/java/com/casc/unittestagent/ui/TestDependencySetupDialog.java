package com.casc.unittestagent.ui;

import com.casc.unittestagent.dependency.TestDependencySetupService;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.ui.DialogWrapper;

import javax.swing.*;
import java.awt.*;

/** A concrete module-scoped preview, not a request to change global repositories or SDKs. */
public final class TestDependencySetupDialog extends DialogWrapper {
    private final TestDependencySetupService service;
    private final TestDependencySetupService.Context context;
    private final JComboBox<String> mode = new JComboBox<>(new String[]{
            "项目配置：写入 Maven/Gradle（使用项目已有仓库）", "仅 IDEA：离线导入内置 JAR"});
    private final JTextArea summary = new JTextArea(16, 68);
    private final JButton diff = new JButton("查看构建文件差异");
    private TestDependencySetupService.Plan plan;

    public TestDependencySetupDialog(TestDependencySetupService service, TestDependencySetupService.Context context) {
        super(context.project());
        this.service = service;
        this.context = context;
        setTitle("配置测试依赖");
        if (context.buildFile() == null) mode.setSelectedIndex(1);
        init();
        mode.addActionListener(event -> update());
        diff.addActionListener(event -> {
            if (plan == null) return;
            DiffManager.getInstance().showDiff(context.project(), new SimpleDiffRequest("测试依赖变更",
                    DiffContentFactory.getInstance().create(context.project(), context.before()),
                    DiffContentFactory.getInstance().create(context.project(), plan.after()), "当前内容", "应用后"));
        });
        update();
    }

    public TestDependencySetupService.Plan getPlan() { return plan; }

    private void update() {
        try {
            plan = service.plan(context, mode.getSelectedIndex() == 0
                    ? TestDependencySetupService.Mode.PROJECT : TestDependencySetupService.Mode.IDEA_OFFLINE);
            summary.setText(plan.summary());
            summary.setCaretPosition(0);
            diff.setEnabled(plan.mode() == TestDependencySetupService.Mode.PROJECT && !context.before().equals(plan.after()));
            setErrorText(null);
            setOKActionEnabled(true);
            setOKButtonText(mode.getSelectedIndex() == 0 ? "应用并刷新项目" : "离线导入");
        } catch (RuntimeException error) {
            plan = null;
            summary.setText(context.note());
            diff.setEnabled(false);
            setErrorText(error.getMessage());
            setOKActionEnabled(false);
        }
    }

    @Override protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.add(mode, BorderLayout.NORTH);
        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        panel.add(new JScrollPane(summary), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.add(diff);
        bottom.add(new JLabel("已有构建文件会先备份；不替换项目仓库配置。"));
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }
}
