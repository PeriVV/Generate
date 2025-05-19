package Action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import javax.swing.*;
import java.awt.*;
import java.io.*;

public class FitTestCasesToNNAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        JDialog dialog = new JDialog();
        dialog.setTitle("拟合测试用例到神经网络模型");
        dialog.setSize(800, 400);
        dialog.setLayout(new BorderLayout());
        dialog.setLocationRelativeTo(null);

        // 顶部：输入输出路径区域
        JPanel filePanel = new JPanel(new GridLayout(3, 1));

        // 输入路径
        JPanel inputPanel = new JPanel(new BorderLayout());
        JTextField inputField = new JTextField();
        JButton inputBrowse = new JButton("选择输入文件");
        inputPanel.add(new JLabel("测试用例输入文件："), BorderLayout.WEST);
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(inputBrowse, BorderLayout.EAST);

        // 输出路径
        JPanel outputPanel = new JPanel(new BorderLayout());
        JTextField outputField = new JTextField();
        JButton outputBrowse = new JButton("选择输出文件");
        outputPanel.add(new JLabel("测试用例输出文件："), BorderLayout.WEST);
        outputPanel.add(outputField, BorderLayout.CENTER);
        outputPanel.add(outputBrowse, BorderLayout.EAST);

        // 拟合按钮
        JPanel fitPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JButton fitButton = new JButton("开始拟合");
        fitPanel.add(fitButton);

        filePanel.add(inputPanel);
        filePanel.add(outputPanel);
        filePanel.add(fitPanel);
        dialog.add(filePanel, BorderLayout.NORTH);

        // 中部：日志输出区域
        JPanel logPanel = new JPanel(new BorderLayout());
        JTextArea logArea = new JTextArea(10, 70);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(logArea);
        logPanel.add(new JLabel("拟合详细信息："), BorderLayout.NORTH);
        logPanel.add(scrollPane, BorderLayout.CENTER);
        dialog.add(logPanel, BorderLayout.CENTER);

        // 文件选择事件
        inputBrowse.addActionListener(ev -> {
            JFileChooser fileChooser = new JFileChooser();
            int result = fileChooser.showOpenDialog(dialog);
            if (result == JFileChooser.APPROVE_OPTION) {
                File selected = fileChooser.getSelectedFile();
                inputField.setText(selected.getAbsolutePath());
            }
        });

        outputBrowse.addActionListener(ev -> {
            JFileChooser fileChooser = new JFileChooser();
            int result = fileChooser.showOpenDialog(dialog);
            if (result == JFileChooser.APPROVE_OPTION) {
                File selected = fileChooser.getSelectedFile();
                outputField.setText(selected.getAbsolutePath());
            }
        });

        // 开始拟合按钮
        fitButton.addActionListener(ev -> {
            String inputPath = inputField.getText().trim();
            String outputPath = outputField.getText().trim();

            if (inputPath.isEmpty() || outputPath.isEmpty()) {
                Messages.showErrorDialog(project, "请输入输入和输出文件路径！", "路径缺失");
                return;
            }

            logArea.append("开始拟合...\n");
            logArea.append("输入文件: " + inputPath + "\n");
            logArea.append("输出文件: " + outputPath + "\n");

            String trainScript = "/Users/weiwei/个人文件夹/BIT/研究生课程/个人实验/neuron_coverage/train.py";

            new Thread(() -> {
                runPythonScript(trainScript, logArea, inputPath, outputPath);
                SwingUtilities.invokeLater(() -> logArea.append("全部执行完成。\n"));
            }).start();
        });

        dialog.setModal(true);
        dialog.setVisible(true);
    }

    private void runPythonScript(String scriptPath, JTextArea logArea, String inputPath, String outputPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/usr/bin/python3", scriptPath, inputPath, outputPath);
            pb.redirectErrorStream(true);

            // 设置 Python 的工作目录为脚本所在目录
            File scriptFile = new File(scriptPath);
            pb.directory(scriptFile.getParentFile());


            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String finalLine = line;
                    SwingUtilities.invokeLater(() -> logArea.append(finalLine + "\n"));
                }
            }

            int exitCode = process.waitFor();
            SwingUtilities.invokeLater(() ->
                    logArea.append("脚本执行结束（退出码：" + exitCode + "）\n\n"));

        } catch (Exception ex) {
            SwingUtilities.invokeLater(() ->
                    logArea.append("执行脚本时出错：" + ex.getMessage() + "\n"));
        }
    }
}
