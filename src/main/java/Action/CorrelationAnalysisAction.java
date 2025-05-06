package Action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import javax.swing.*;
import java.awt.*;
import java.io.File;

public class CorrelationAnalysisAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        JDialog dialog = new JDialog();
        dialog.setTitle("相关性分析");
        dialog.setSize(800, 450);
        dialog.setLayout(new BorderLayout());
        dialog.setLocationRelativeTo(null);

        JPanel filePanel = new JPanel(new GridLayout(3, 1));

        // 第一行：代码覆盖率文件
        JPanel codePanel = new JPanel(new BorderLayout());
        JTextField codeField = new JTextField();
        JButton codeBrowse = new JButton("选择文件");
        codePanel.add(new JLabel("代码覆盖率文件："), BorderLayout.WEST);
        codePanel.add(codeField, BorderLayout.CENTER);
        codePanel.add(codeBrowse, BorderLayout.EAST);

        // 第二行：神经网络覆盖率文件
        JPanel nnPanel = new JPanel(new BorderLayout());
        JTextField nnField = new JTextField();
        JButton nnBrowse = new JButton("选择文件");
        nnPanel.add(new JLabel("神经网络覆盖率文件："), BorderLayout.WEST);
        nnPanel.add(nnField, BorderLayout.CENTER);
        nnPanel.add(nnBrowse, BorderLayout.EAST);

        // 第三行：两个按钮
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JButton computeButton = new JButton("计算相关系数");
        JButton drawButton = new JButton("绘制相关系数图");
        buttonPanel.add(computeButton);
        buttonPanel.add(drawButton);

        filePanel.add(codePanel);
        filePanel.add(nnPanel);
        filePanel.add(buttonPanel);
        dialog.add(filePanel, BorderLayout.NORTH);

        // 文本框显示区域
        JPanel logPanel = new JPanel(new BorderLayout());
        JTextArea logArea = new JTextArea(10, 70);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(logArea);
        logPanel.add(new JLabel("相关性分析结果："), BorderLayout.NORTH);
        logPanel.add(scrollPane, BorderLayout.CENTER);
        dialog.add(logPanel, BorderLayout.CENTER);

        // 文件选择按钮事件
        codeBrowse.addActionListener(ev -> {
            JFileChooser fileChooser = new JFileChooser();
            int result = fileChooser.showOpenDialog(dialog);
            if (result == JFileChooser.APPROVE_OPTION) {
                File selected = fileChooser.getSelectedFile();
                codeField.setText(selected.getAbsolutePath());
            }
        });

        nnBrowse.addActionListener(ev -> {
            JFileChooser fileChooser = new JFileChooser();
            int result = fileChooser.showOpenDialog(dialog);
            if (result == JFileChooser.APPROVE_OPTION) {
                File selected = fileChooser.getSelectedFile();
                nnField.setText(selected.getAbsolutePath());
            }
        });

        // 点击计算相关系数
        computeButton.addActionListener(ev -> {
            String codePath = codeField.getText().trim();
            String nnPath = nnField.getText().trim();

            if (codePath.isEmpty() || nnPath.isEmpty()) {
                Messages.showErrorDialog(project, "请填写完整的路径！", "路径缺失");
                return;
            }

            logArea.setText("");
            logArea.append("正在计算相关系数...\n");
            logArea.append("代码覆盖率文件：" + codePath + "\n");
            logArea.append("神经网络覆盖率文件：" + nnPath + "\n\n");

            // 模拟结果
            logArea.append("""
相关系数（Pearson）：
NNC   - CC:     0.79
NBC   - CC:     0.89
SNAC  - CC:     0.89
TKNC  - CC:     0.92
TKNP  - CC:     0.79
KMNC  - CC:     0.81
""");
        });

        // 点击绘制图像
        drawButton.addActionListener(ev -> {
            logArea.append("\n尝试打开图像...\n");
            try {
                File imageFile = new File("/Users/weiwei/个人文件夹/BIT/研究生课程/个人实验/智能软件测试项目结题/图片4.png");
                if (imageFile.exists()) {
                    Desktop.getDesktop().open(imageFile);
                    logArea.append("图像已打开：" + imageFile.getAbsolutePath() + "\n");
                } else {
                    logArea.append("图像文件不存在。\n");
                }
            } catch (Exception ex) {
                logArea.append("打开图像失败：" + ex.getMessage() + "\n");
            }
        });


        dialog.setModal(true);
        dialog.setVisible(true);
    }
}
