package com.casc.unittestagent.util;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;

/**
 * 封装 IntelliJ PSI 相关的小型工具方法。
 * 该类把源码文本、当前方法、包名和虚拟文件获取逻辑集中起来，降低动作和服务层对 PSI 细节的耦合。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class PsiUtils {
    /**
     * 禁止创建 PSI 工具类实例。
     * 所有方法都是无状态静态工具方法，私有构造可以避免调用方误以为该类需要保存上下文。
     */
    private PsiUtils() {
    }

    /**
     * 读取 PSI 文件的源码文本。
     * 调用方不需要重复处理空文件场景，空输入会统一返回空字符串。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param psiFile 当前 PSI 文件，通常是用户正在编辑的 Java 源文件
     * @return 源码文本，文件为空时返回空字符串
     */
    public static String getSourceText(PsiFile psiFile) {
        return psiFile == null ? "" : psiFile.getText();
    }

    /**
     * 根据编辑器光标定位当前方法名。
     * 生成流程用它优先分析用户正在关注的方法，而不是盲目选择文件中的第一个方法。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param psiFile 当前 PSI 文件，通常是用户正在编辑的 Java 源文件
     * @param editor 当前编辑器，用于读取光标位置并定位优先分析的方法
     * @return 光标所在方法名，无法定位时返回 null
     */
    public static String getCurrentMethodName(PsiFile psiFile, Editor editor) {
        if (psiFile == null || editor == null) {
            return null;
        }

        int offset = editor.getCaretModel().getOffset();
        PsiElement elementAt = psiFile.findElementAt(offset);
        PsiMethod method = PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class, false);
        return method == null ? null : method.getName();
    }

    /**
     * 读取 Java 文件包名。
     * 非 Java 文件返回空字符串，调用方可以保持简单的包名处理逻辑。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param psiFile 当前 PSI 文件，通常是用户正在编辑的 Java 源文件
     * @return 包名文本
     */
    public static String getPackageName(PsiFile psiFile) {
        if (psiFile instanceof PsiJavaFile) {
            return ((PsiJavaFile) psiFile).getPackageName();
        }
        return "";
    }

    /**
     * 读取 PSI 文件对应的虚拟文件。
     * 写入模块需要虚拟文件路径来推导测试文件位置。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param psiFile 当前 PSI 文件，通常是用户正在编辑的 Java 源文件
     * @return PSI 对应的虚拟文件，无法获取时返回 null
     */
    public static VirtualFile getVirtualFile(PsiFile psiFile) {
        return psiFile == null ? null : psiFile.getVirtualFile();
    }
}
