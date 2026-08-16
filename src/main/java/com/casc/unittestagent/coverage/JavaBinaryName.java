package com.casc.unittestagent.coverage;

import java.io.File;

/** Java 源码限定名与 JVM 二进制类名之间的转换。 */
public final class JavaBinaryName {
    private JavaBinaryName() {
    }

    /**
     * PSI 对嵌套类返回 {@code package.Outer.Inner}，而 class 文件和 JaCoCo 使用
     * {@code package.Outer$Inner}。包名来自当前源码，因此可以无歧义地转换类名部分。
     */
    public static String fromSourceQualifiedName(String qualifiedName, String packageName) {
        String name = qualifiedName == null ? "" : qualifiedName.trim();
        String pkg = packageName == null ? "" : packageName.trim();
        if (name.isEmpty()) return name;
        if (!pkg.isEmpty() && name.startsWith(pkg + ".")) {
            String classPart = name.substring(pkg.length() + 1).replace('.', '$');
            return pkg + "." + classPart;
        }
        return pkg.isEmpty() ? name.replace('.', '$') : name;
    }

    public static String classFileRelativePath(String binaryName) {
        return (binaryName == null ? "" : binaryName.trim()).replace('.', File.separatorChar) + ".class";
    }
}
