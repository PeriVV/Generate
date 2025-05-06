package Action;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

public class SelectTestCaseAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        JDialog dialog = new JDialog();
        dialog.setTitle("选择项目与方法");
        dialog.setSize(600, 200);
        dialog.setLayout(new GridLayout(3, 1));
        dialog.setLocationRelativeTo(null);

        // 顶部：路径选择
        JPanel pathPanel = new JPanel(new BorderLayout());
        JTextField pathField = new JTextField();
        JButton browseButton = new JButton("选择项目路径");
        pathPanel.add(new JLabel("项目路径："), BorderLayout.WEST);
        pathPanel.add(pathField, BorderLayout.CENTER);
        pathPanel.add(browseButton, BorderLayout.EAST);
        dialog.add(pathPanel);

        // 中间：方法下拉框
        JPanel methodPanel = new JPanel(new BorderLayout());
        JComboBox<String> methodBox = new JComboBox<>();
        methodPanel.add(new JLabel("可选方法："), BorderLayout.WEST);
        methodPanel.add(methodBox, BorderLayout.CENTER);
        dialog.add(methodPanel);

        // 底部：确认按钮
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton confirmButton = new JButton("确定");
        buttonPanel.add(confirmButton);
        dialog.add(buttonPanel);

        // 浏览按钮事件
        browseButton.addActionListener(ev -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int result = chooser.showOpenDialog(dialog);
            if (result == JFileChooser.APPROVE_OPTION) {
                File projectDir = chooser.getSelectedFile();
                pathField.setText(projectDir.getAbsolutePath());
                List<String> methods = extractMethodSignatures(projectDir);
                methodBox.removeAllItems();
                for (String m : methods) {
                    methodBox.addItem(m);
                }
            }
        });

        // 确认按钮事件
        confirmButton.addActionListener(ev -> {
            String selected = (String) methodBox.getSelectedItem();
            dialog.dispose();
            if (selected != null && project != null) {
                Messages.showInfoMessage(project, "你选择了方法：\n" + selected, "方法已选中");
            }
        });

        dialog.setModal(true);
        dialog.setVisible(true);
    }

    private List<String> extractMethodSignatures(File root) {
        List<String> result = new ArrayList<>();
        scanJavaFiles(root, result);
        return result;
    }

    private void scanJavaFiles(File file, List<String> result) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                scanJavaFiles(child, result);
            }
        } else if (file.getName().endsWith(".java")) {
            try (FileInputStream in = new FileInputStream(file)) {
                CompilationUnit cu = StaticJavaParser.parse(in);
                cu.findAll(MethodDeclaration.class).forEach(method -> {
                    result.add(file.getName() + " :: " + method.getDeclarationAsString(false, false, true));
                });
            } catch (Exception ignored) {}
        }
    }
}
