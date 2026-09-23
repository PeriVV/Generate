package com.casc.unittestagent.generator;

import java.io.File;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 基于真实 classpath 的类型可实例化探针。
 * 规则生成器需要为未知引用类型决定 new X() 还是 null/mock：名称启发式
 * （looksLikeExternalInterface 的后缀表）无法覆盖第三方接口（spring-petclinic 上
 * 实测生成了 new Errors()——Errors 是接口，必然编译失败）。此探针对 classpath
 * （target/classes 目录 + 依赖 jar）建立简单名 → FQN 索引，用反射判定
 * 接口/抽象类/无公共无参构造；任一候选不可实例化即保守返回不可，生成端退回
 * null 由 mock 策略或断言结构兜底。
 */
public final class ClasspathTypeProbe implements Predicate<String> {
    private final List<File> classpath;
    private Map<String, Set<String>> simpleNameIndex;
    private URLClassLoader loader;

    public static ClasspathTypeProbe from(List<File> classpath) {
        return new ClasspathTypeProbe(classpath);
    }

    private ClasspathTypeProbe(List<File> classpath) {
        this.classpath = classpath == null ? List.of() : classpath;
    }

    /**
     * 判断以简单名引用的类型能否生成 new X()。
     * 找不到任何候选（索引里没有、JDK 里也没有）时返回 false——宁可退 null
     * 保编译，也不要赌一个可能存在的无参构造。
     */
    @Override
    public boolean test(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) return false;
        String name = simpleName.strip();
        int generic = name.indexOf('<');
        if (generic >= 0) name = name.substring(0, generic).strip();
        int dot = name.lastIndexOf('.');
        String simple = dot >= 0 ? name.substring(dot + 1) : name;
        List<String> candidates = new ArrayList<>(index().getOrDefault(simple, Set.of()));
        if (dot >= 0 && !candidates.contains(name)) {
            // 已经是限定名：直接作为候选，索引缺失也能判。
            candidates.add(name);
        }
        if (candidates.isEmpty()) return false;
        boolean anyInstantiable = false;
        for (String qualified : candidates) {
            Class<?> clazz;
            try {
                clazz = Class.forName(qualified, false, classLoader());
            } catch (Throwable resolutionFailure) {
                return false;
            }
            int modifiers = clazz.getModifiers();
            if (Modifier.isInterface(modifiers) || Modifier.isAbstract(modifiers)) {
                return false;
            }
            try {
                clazz.getConstructor();
                anyInstantiable = true;
            } catch (NoSuchMethodException missingNoArgConstructor) {
                return false;
            }
        }
        return anyInstantiable;
    }

    private synchronized Map<String, Set<String>> index() {
        if (simpleNameIndex != null) return simpleNameIndex;
        Map<String, Set<String>> index = new HashMap<>();
        for (File entry : classpath) {
            if (entry == null || !entry.exists()) continue;
            if (entry.isDirectory()) {
                indexDirectory(entry, entry, index);
            } else {
                indexJar(entry, index);
            }
        }
        simpleNameIndex = index;
        return simpleNameIndex;
    }

    private void indexDirectory(File root, File directory, Map<String, Set<String>> index) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                indexDirectory(root, child, index);
            } else if (child.getName().endsWith(".class")) {
                addCandidate(index, relativeQualifiedName(root, child));
            }
        }
    }

    private void indexJar(File jar, Map<String, Set<String>> index) {
        try (java.util.jar.JarFile opened = new java.util.jar.JarFile(jar)) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = opened.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                if (!entry.endsWith(".class") || entry.startsWith("META-INF")
                        || entry.endsWith("module-info.class")) {
                    continue;
                }
                addCandidate(index, entry.substring(0, entry.length() - ".class".length()).replace('/', '.'));
            }
        } catch (Exception unreadableJar) {
            // 不可读的依赖不影响其余索引；对应类型会保守判为不可实例化。
        }
    }

    private void addCandidate(Map<String, Set<String>> index, String qualifiedName) {
        if (qualifiedName.isBlank() || qualifiedName.indexOf('/') >= 0) return;
        int dot = qualifiedName.lastIndexOf('.');
        String simple = dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
        if (simple.isBlank() || simple.contains("$")) return;
        index.computeIfAbsent(simple, ignored -> new LinkedHashSet<>()).add(qualifiedName);
    }

    private String relativeQualifiedName(File root, File file) {
        String relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        return relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
    }

    private synchronized URLClassLoader classLoader() {
        if (loader != null) return loader;
        List<URL> urls = new ArrayList<>();
        for (File entry : classpath) {
            if (entry == null || !entry.exists()) continue;
            try {
                urls.add(entry.toURI().toURL());
            } catch (Exception ignored) {
                // 无效路径跳过；对应类型经索引缺失保守处理。
            }
        }
        // 父委托保留 JDK/插件类可见性：java.lang 等平台类型不来自 classpath。
        loader = new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
        return loader;
    }
}
