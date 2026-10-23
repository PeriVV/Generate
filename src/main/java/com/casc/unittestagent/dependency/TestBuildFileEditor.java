package com.casc.unittestagent.dependency;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Edits an in-memory copy only. The caller applies the reviewed snapshot later. */
public final class TestBuildFileEditor {
    public record Dependency(String group, String artifact, String version) { }
    public static final String BEGIN = "// UnitMind test dependencies BEGIN";
    public static final String END = "// UnitMind test dependencies END";

    public static String maven(Project project, String original, List<Dependency> dependencies, boolean junit) {
        AtomicReference<String> result = new AtomicReference<>();
        Runnable edit = () -> ApplicationManager.getApplication().runWriteAction(() -> {
            XmlFile copy = (XmlFile) PsiFileFactory.getInstance(project).createFileFromText("pom.xml", XmlFileType.INSTANCE, original);
            XmlTag root = copy.getRootTag();
            if (root == null || !root.getLocalName().equals("project") || original.contains("<!DOCTYPE")) {
                throw new IllegalArgumentException("pom.xml 不是有效的 Maven 项目，或包含不支持的 DOCTYPE。");
            }
            if (com.intellij.psi.util.PsiTreeUtil.findChildOfType(copy, com.intellij.psi.PsiErrorElement.class) != null) {
                throw new IllegalArgumentException("pom.xml 含有语法错误，请先修复。");
            }
            List<XmlTag> changed = new ArrayList<>();
            for (Dependency dependency : dependencies) {
                XmlTag existing = child(root, "dependencies");
                if (existing != null && Arrays.stream(existing.getSubTags()).anyMatch(tag -> matches(tag, dependency))) continue;
                XmlTag container = ensure(root, "dependencies");
                XmlTag tag = append(container, "dependency");
                value(tag, "groupId", dependency.group());
                value(tag, "artifactId", dependency.artifact());
                boolean managed = managed(root, dependency);
                String version = dependency.version();
                if (dependency.artifact().equals("mockito-junit-jupiter")) {
                    XmlTag core = Arrays.stream(container.getSubTags())
                            .filter(item -> matches(item, new Dependency("org.mockito", "mockito-core", ""))).findFirst().orElse(null);
                    if (core != null && !text(core, "version").isBlank()) version = text(core, "version");
                }
                if (!managed) value(tag, "version", version);
                value(tag, "scope", "test");
                changed.add(tag);
            }
            if (junit) {
                XmlTag plugins = ensure(ensure(root, "build"), "plugins");
                boolean present = Arrays.stream(plugins.getSubTags()).anyMatch(tag -> text(tag, "artifactId").equals("maven-surefire-plugin"));
                if (!present) {
                    XmlTag plugin = append(plugins, "plugin");
                    value(plugin, "groupId", "org.apache.maven.plugins");
                    value(plugin, "artifactId", "maven-surefire-plugin");
                    XmlTag management = child(child(root, "build"), "pluginManagement");
                    XmlTag managedPlugins = child(management, "plugins");
                    XmlTag inherited = managedPlugins == null ? null : Arrays.stream(managedPlugins.getSubTags())
                            .filter(tag -> text(tag, "artifactId").equals("maven-surefire-plugin")).findFirst().orElse(null);
                    if (inherited == null || oldSurefire(text(inherited, "version"))) value(plugin, "version", "3.5.4");
                    changed.add(plugin);
                } else {
                    XmlTag plugin = Arrays.stream(plugins.getSubTags()).filter(tag -> text(tag, "artifactId").equals("maven-surefire-plugin")).findFirst().orElseThrow();
                    String version = text(plugin, "version");
                    if (oldSurefire(version)) {
                        child(plugin, "version").getValue().setText("3.5.4");
                        changed.add(plugin);
                    }
                }
            }
            for (XmlTag tag : changed) CodeStyleManager.getInstance(project).reformat(tag);
            result.set(copy.getText());
        });
        if (ApplicationManager.getApplication().isDispatchThread()) edit.run();
        else ApplicationManager.getApplication().invokeAndWait(edit);
        return result.get();
    }

    private static boolean oldSurefire(String version) {
        var match = Pattern.compile("(\\d+)\\.(\\d+).*?").matcher(version);
        return match.matches() && (Integer.parseInt(match.group(1)) < 2
                || Integer.parseInt(match.group(1)) == 2 && Integer.parseInt(match.group(2)) < 22);
    }

    private static boolean managed(XmlTag root, Dependency dependency) {
        XmlTag management = child(child(root, "dependencyManagement"), "dependencies");
        if (management != null) for (XmlTag tag : management.getSubTags()) {
            if (matches(tag, dependency)) return true;
            String artifact = text(tag, "artifactId");
            if (text(tag, "scope").equals("import") && (artifact.equals("spring-boot-dependencies")
                    || dependency.group().equals("org.junit.jupiter") && artifact.equals("junit-bom")
                    || dependency.group().equals("org.mockito") && artifact.equals("mockito-bom"))) return true;
        }
        return text(child(root, "parent"), "artifactId").equals("spring-boot-starter-parent");
    }

    public static String gradle(String original, List<Dependency> dependencies, boolean junit, boolean kotlin) {
        // Never rewrite a user-edited managed block or add it again while IDE import is pending.
        if (original.contains(BEGIN)) {
            if (!original.contains(END)) throw new IllegalArgumentException("UnitMind 依赖配置区块不完整，请检查构建文件。");
            return original;
        }
        String code = stripComments(original);
        List<Dependency> missing = dependencies.stream().filter(dependency -> !Pattern.compile(
                "[\"']" + Pattern.quote(dependency.group() + ":" + dependency.artifact()) + "(?::[^\"']*)?[\"']")
                .matcher(code).find()).toList();
        boolean launcher = junit && !code.contains("org.junit.platform:junit-platform-launcher");
        if (missing.isEmpty() && !launcher && (!junit || code.contains("useJUnitPlatform"))) return original;
        if (junit && (code.contains("useTestNG(") || code.contains("useJUnit("))) {
            throw new IllegalArgumentException("项目已显式选择其他测试引擎。请使用 IDEA 离线模式，或先确认 Gradle 测试引擎迁移方案。");
        }
        String nl = original.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder block = new StringBuilder(nl).append(BEGIN).append(nl);
        if (!missing.isEmpty() || launcher) {
            block.append("dependencies {").append(nl);
            for (Dependency dependency : missing) {
                boolean managed = code.contains("org.springframework.boot:spring-boot-dependencies")
                        || dependency.group().equals("org.junit.jupiter") && code.contains("org.junit:junit-bom")
                        || dependency.group().equals("org.mockito") && code.contains("org.mockito:mockito-bom");
                String version = dependency.version();
                if (dependency.artifact().equals("mockito-junit-jupiter")) {
                    var core = Pattern.compile("[\"']org\\.mockito:mockito-core:([^\"']+)[\"']").matcher(code);
                    if (core.find()) version = core.group(1);
                }
                block.append("    add(\"testImplementation\", \"").append(dependency.group()).append(':')
                        .append(dependency.artifact());
                if (!managed) block.append(':').append(version);
                block.append("\")").append(nl);
            }
            if (launcher) {
                String jupiter = dependencies.stream().filter(dependency -> dependency.group().equals("org.junit.jupiter"))
                        .map(Dependency::version).findFirst().orElse("5.12.0");
                String platform = jupiter.startsWith("5.") ? "1." + jupiter.substring(2) : jupiter;
                boolean managed = code.contains("org.junit:junit-bom") || code.contains("org.springframework.boot:spring-boot-dependencies");
                block.append("    add(\"testRuntimeOnly\", \"org.junit.platform:junit-platform-launcher");
                if (!managed) block.append(':').append(platform);
                block.append("\")").append(nl);
            }
            block.append('}').append(nl);
        }
        if (junit && !code.contains("useJUnitPlatform")) {
            block.append(kotlin ? "tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {"
                    : "tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {").append(nl)
                    .append("    useJUnitPlatform()").append(nl).append('}').append(nl);
        }
        block.append(END).append(nl);
        return original + block;
    }

    private static String stripComments(String text) {
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            if (text.startsWith("//", index)) {
                int end = text.indexOf('\n', index); index = end < 0 ? text.length() : end;
            } else if (text.startsWith("/*", index)) {
                int end = text.indexOf("*/", index + 2); index = end < 0 ? text.length() : end + 2;
            } else if (text.charAt(index) == '\'' || text.charAt(index) == '"') {
                char quote = text.charAt(index);
                result.append(text.charAt(index++));
                while (index < text.length()) {
                    char ch = text.charAt(index++); result.append(ch);
                    if (ch == '\\' && index < text.length()) result.append(text.charAt(index++));
                    else if (ch == quote) break;
                }
            } else result.append(text.charAt(index++));
        }
        return result.toString();
    }

    private static XmlTag child(XmlTag parent, String name) {
        return parent == null ? null : Arrays.stream(parent.getSubTags()).filter(tag -> tag.getLocalName().equals(name)).findFirst().orElse(null);
    }
    private static String text(XmlTag parent, String name) {
        XmlTag tag = child(parent, name); return tag == null ? "" : tag.getValue().getTrimmedText();
    }
    private static boolean matches(XmlTag tag, Dependency dependency) {
        return tag.getLocalName().equals("dependency") && text(tag, "groupId").equals(dependency.group())
                && text(tag, "artifactId").equals(dependency.artifact());
    }
    private static XmlTag ensure(XmlTag parent, String name) {
        XmlTag tag = child(parent, name); return tag == null ? append(parent, name) : tag;
    }
    private static XmlTag append(XmlTag parent, String name) {
        return parent.addSubTag(parent.createChildTag(name, parent.getNamespace(), null, false), false);
    }
    private static void value(XmlTag parent, String name, String text) { append(parent, name).getValue().setText(text); }
}
