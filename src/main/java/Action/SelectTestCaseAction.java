package Action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import javax.swing.*;
import java.awt.*;
import java.io.File;

public class SelectTestCaseAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        JDialog dialog = new JDialog();
        dialog.setTitle("选择项目路径");
        dialog.setSize(600, 120);
        dialog.setLayout(new BorderLayout());
        dialog.setLocationRelativeTo(null);

        // 路径选择面板
        JPanel pathPanel = new JPanel(new BorderLayout());
        JTextField pathField = new JTextField();
        JButton browseButton = new JButton("选择项目路径");
        pathPanel.add(new JLabel("项目路径："), BorderLayout.WEST);
        pathPanel.add(pathField, BorderLayout.CENTER);
        pathPanel.add(browseButton, BorderLayout.EAST);
        dialog.add(pathPanel, BorderLayout.NORTH);

        // 浏览按钮逻辑
        browseButton.addActionListener(ev -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int result = chooser.showOpenDialog(dialog);
            if (result == JFileChooser.APPROVE_OPTION) {
                File projectDir = chooser.getSelectedFile();
                pathField.setText(projectDir.getAbsolutePath());
                Messages.showInfoMessage(project, "你选择的路径是：\n" + projectDir.getAbsolutePath(), "路径已选择");
                dialog.dispose();
            }
        });

        dialog.setModal(true);
        dialog.setVisible(true);
    }
}
