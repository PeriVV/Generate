package com.casc.unittestagent.ui;

import com.casc.unittestagent.coverage.CoverageConfig;
import com.casc.unittestagent.coverage.CoverageConfigManager;
import com.casc.unittestagent.llm.LlmConfig;
import com.casc.unittestagent.llm.LlmConfigManager;
import com.casc.unittestagent.llm.OpenAiCompatibleLlmClient;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * UnitMind 设置窗口。
 * 该窗口保留测试数据范围配置入口，并新增 OpenAI-compatible 大模型配置。
 */
public class UnitMindSettingsDialog extends DialogWrapper {
    private final Project project;
    private final JCheckBox enabled = new JCheckBox("启用大模型");
    private final JTextField apiUrl = new JTextField();
    private final JPasswordField apiKey = new JPasswordField();
    private final JTextField model = new JTextField();
    private final JSpinner timeoutSeconds = new JSpinner(new SpinnerNumberModel(60, 1, 600, 1));
    private final JSpinner maxTokens = new JSpinner(new SpinnerNumberModel(12000, 256, 32000, 256));
    private final JSpinner compileRepairRounds = new JSpinner(new SpinnerNumberModel(5, 0, 30, 1));
    private final JSpinner temperature = new JSpinner(new SpinnerNumberModel(0.2, 0.0, 2.0, 0.1));
    private final JCheckBox fallbackToRuleBased = new JCheckBox("失败时使用规则兜底");
    private final JLabel apiKeyStatus = new JLabel();
    private final JButton testConnection = new JButton("测试连接");
    private final JSpinner lineCoverage = new JSpinner(new SpinnerNumberModel(90, 0, 100, 1));
    private final JSpinner branchCoverage = new JSpinner(new SpinnerNumberModel(80, 0, 100, 1));
    private final JSpinner coverageRounds = new JSpinner(new SpinnerNumberModel(5, 0, 30, 1));
    private final JSpinner coverageRepairRounds = new JSpinner(new SpinnerNumberModel(5, 0, 30, 1));
    private final JSpinner coverageTimeout = new JSpinner(new SpinnerNumberModel(60, 5, 600, 5));
    private final JCheckBox existingBaseline = new JCheckBox("计算已有 <被测类>Test 基线");

    public UnitMindSettingsDialog(@Nullable Project project) {
        super(project);
        this.project = project;
        setTitle("UnitMind 设置");
        setOKButtonText("保存");
        setCancelButtonText("取消");
        load();
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.add(testDataPanel(), BorderLayout.NORTH);
        JPanel settings = new JPanel(new BorderLayout(0, 12));
        settings.add(llmPanel(), BorderLayout.CENTER);
        settings.add(coveragePanel(), BorderLayout.SOUTH);
        root.add(settings, BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(720, 520));
        return root;
    }

    @Override
    protected void doOKAction() {
        boolean wasEnabled = LlmConfigManager.load().isEnabled();
        if (!wasEnabled && enabled.isSelected() && !LlmConfigManager.isPrivacyConfirmed()) {
            int confirmed = Messages.showOkCancelDialog(project,
                    "UnitMind 将把当前被测方法源码、静态分析结果和测试场景发送到你配置的大模型接口，用于生成 Mock 策略和测试代码。请确认该接口符合你的代码安全要求。",
                    "UnitMind 大模型安全提示",
                    "确认启用",
                    "取消",
                    Messages.getWarningIcon());
            if (confirmed != Messages.OK) {
                return;
            }
            LlmConfigManager.setPrivacyConfirmed(true);
        }

        LlmConfig config = configFromUi();
        if (!validateConfigForSave(config)) {
            return;
        }
        LlmConfigManager.save(config, new String(apiKey.getPassword()));
        CoverageConfig coverage = new CoverageConfig();
        coverage.setLineThreshold(((Integer) lineCoverage.getValue()) / 100.0d);
        coverage.setBranchThreshold(((Integer) branchCoverage.getValue()) / 100.0d);
        coverage.setMaxRounds((Integer) coverageRounds.getValue());
        coverage.setMaxRepairRounds((Integer) coverageRepairRounds.getValue());
        coverage.setExecutionTimeoutSeconds((Integer) coverageTimeout.getValue());
        coverage.setExistingBaselineEnabled(existingBaseline.isSelected());
        CoverageConfigManager.save(coverage);
        super.doOKAction();
    }

    private JPanel testDataPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton testDataConfig = new JButton("测试数据范围配置");
        testDataConfig.addActionListener(event -> new TestDataRangeConfigDialog(project).show());
        panel.add(testDataConfig);
        return panel;
    }

    private JPanel llmPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        panel.add(enabled, constraints);

        addRow(panel, constraints, 1, "API 地址", apiUrl);
        addRow(panel, constraints, 2, "API Key", apiKey);
        addRow(panel, constraints, 3, "模型名称", model);
        addRow(panel, constraints, 4, "请求超时时间", timeoutSeconds);
        addRow(panel, constraints, 5, "最大输出长度", maxTokens);
        addRow(panel, constraints, 6, "温度", temperature);
        addRow(panel, constraints, 7, "最多编译修复轮次", compileRepairRounds);

        constraints.gridx = 1;
        constraints.gridy = 8;
        constraints.gridwidth = 1;
        panel.add(fallbackToRuleBased, constraints);

        constraints.gridx = 1;
        constraints.gridy = 9;
        panel.add(apiKeyStatus, constraints);

        constraints.gridx = 1;
        constraints.gridy = 10;
        testConnection.addActionListener(event -> testConnection());
        panel.add(testConnection, constraints);

        constraints.gridx = 1;
        constraints.gridy = 11;
        panel.add(new JLabel("API Key 优先读取环境变量 " + LlmConfigManager.apiKeyEnvironmentName() + "；输入新 Key 后保存到 IDEA PasswordSafe。"), constraints);
        return panel;
    }

    private JPanel coveragePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(javax.swing.BorderFactory.createTitledBorder("测试覆盖率闭环"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        addRow(panel, constraints, 0, "行覆盖率目标（%）", lineCoverage);
        addRow(panel, constraints, 1, "分支覆盖率目标（%）", branchCoverage);
        addRow(panel, constraints, 2, "最多覆盖率探索轮次", coverageRounds);
        addRow(panel, constraints, 3, "最多失败修复轮次", coverageRepairRounds);
        addRow(panel, constraints, 4, "单轮执行超时（秒）", coverageTimeout);
        constraints.gridx = 1;
        constraints.gridy = 5;
        constraints.weightx = 1;
        panel.add(existingBaseline, constraints);
        return panel;
    }

    private void addRow(JPanel panel, GridBagConstraints constraints, int row, String label, JComponent component) {
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        constraints.gridx = 0;
        constraints.gridy = row;
        panel.add(new JLabel(label), constraints);
        constraints.weightx = 1;
        constraints.gridx = 1;
        panel.add(component, constraints);
    }

    private void load() {
        LlmConfig config = LlmConfigManager.load();
        enabled.setSelected(config.isEnabled());
        apiUrl.setText(config.getApiUrl());
        apiKey.setText("");
        apiKey.setToolTipText(config.maskedApiKey());
        apiKeyStatus.setText(config.maskedApiKey().isBlank() ? "未保存 API Key" : "已保存 API Key：" + config.maskedApiKey());
        model.setText(config.getModel());
        timeoutSeconds.setValue(config.getTimeoutSeconds());
        maxTokens.setValue(config.getMaxTokens());
        compileRepairRounds.setValue(config.getMaxCompileRepairRounds());
        temperature.setValue(config.getTemperature());
        fallbackToRuleBased.setSelected(config.isFallbackToRuleBased());
        CoverageConfig coverage = CoverageConfigManager.load();
        lineCoverage.setValue((int) Math.round(coverage.getLineThreshold() * 100));
        branchCoverage.setValue((int) Math.round(coverage.getBranchThreshold() * 100));
        coverageRounds.setValue(coverage.getMaxRounds());
        coverageRepairRounds.setValue(coverage.getMaxRepairRounds());
        coverageTimeout.setValue(coverage.getExecutionTimeoutSeconds());
        existingBaseline.setSelected(coverage.isExistingBaselineEnabled());
    }

    private LlmConfig configFromUi() {
        LlmConfig config = new LlmConfig();
        config.setEnabled(enabled.isSelected());
        config.setApiUrl(apiUrl.getText());
        String typedKey = new String(apiKey.getPassword());
        config.setApiKey(typedKey == null || typedKey.isBlank() ? LlmConfigManager.load().getApiKey() : typedKey.trim());
        config.setModel(model.getText());
        config.setTimeoutSeconds((Integer) timeoutSeconds.getValue());
        config.setMaxTokens((Integer) maxTokens.getValue());
        config.setMaxCompileRepairRounds((Integer) compileRepairRounds.getValue());
        config.setTemperature(((Number) temperature.getValue()).doubleValue());
        config.setFallbackToRuleBased(fallbackToRuleBased.isSelected());
        return config;
    }

    private boolean validateConfigForSave(LlmConfig config) {
        if (!config.isEnabled()) {
            return true;
        }
        if (config.getApiUrl() == null || config.getApiUrl().isBlank()) {
            Messages.showErrorDialog(project, "启用大模型时必须配置 API 地址。", "UnitMind 大模型配置错误");
            return false;
        }
        if (config.getModel() == null || config.getModel().isBlank()) {
            Messages.showErrorDialog(project, "启用大模型时必须配置模型名称。", "UnitMind 大模型配置错误");
            return false;
        }
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            Messages.showErrorDialog(project, "启用大模型时必须配置 API Key，或设置环境变量 " + LlmConfigManager.apiKeyEnvironmentName() + "。", "UnitMind 大模型配置错误");
            return false;
        }
        return true;
    }

    private void testConnection() {
        LlmConfig config = configFromUi();
        if (!validateConfigForSave(config)) {
            return;
        }
        testConnection.setEnabled(false);
        testConnection.setText("测试中...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String message = new OpenAiCompatibleLlmClient(config).testConnection();
                SwingUtilities.invokeLater(() -> Messages.showInfoMessage(project, message, "UnitMind 大模型连接成功"));
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                SwingUtilities.invokeLater(() -> Messages.showErrorDialog(project, message, "UnitMind 大模型连接失败"));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    testConnection.setEnabled(true);
                    testConnection.setText("测试连接");
                });
            }
        });
    }
}
