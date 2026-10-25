package com.casc.unittestagent.dependency;

import com.casc.unittestagent.coverage.BundledMockitoRuntime;
import com.casc.unittestagent.coverage.ProjectTestEnvironment;
import com.casc.unittestagent.coverage.ProjectTestEnvironmentResolver;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.DependencyScope;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.util.PathUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

public final class TestDependencySetupService {
    public static final String OFFLINE_LIBRARY = "UnitMind offline test runtime";
    public enum Mode { PROJECT, IDEA_OFFLINE }
    public record Context(Project project, Module module, Path directory, Path buildFile, String before,
                          String diskBeforeHash, Path refreshRoot, List<TestBuildFileEditor.Dependency> dependencies,
                          List<File> offlineJars, boolean junit, String note,
                          java.nio.charset.Charset buildCharset, String lineSeparator, String legacyJUnitVersion) {
        public Context(Project project, Module module, Path directory, Path buildFile, String before,
                       String diskBeforeHash, Path refreshRoot, List<TestBuildFileEditor.Dependency> dependencies,
                       List<File> offlineJars, boolean junit, String note,
                       java.nio.charset.Charset buildCharset, String lineSeparator) {
            this(project, module, directory, buildFile, before, diskBeforeHash, refreshRoot, dependencies,
                    offlineJars, junit, note, buildCharset, lineSeparator, "");
        }
    }
    public record Plan(Context context, Mode mode, String after) {
        public String summary() {
            StringBuilder text = new StringBuilder("模块：").append(context.module().getName())
                    .append("\n目录：").append(context.directory()).append('\n');
            if (mode == Mode.PROJECT) {
                text.append("更新：").append(context.buildFile()).append("\n依赖使用 test/testImplementation 作用域。\n")
                        .append("解析使用项目已有仓库配置，不修改仓库地址。\n");
                text.append("缺省建议（已有声明/BOM 优先，实际内容见差异预览）：\n");
                for (var dependency : context.dependencies()) text.append("  ").append(dependency.group()).append(':')
                        .append(dependency.artifact()).append(':').append(dependency.version()).append('\n');
                if (context.buildFile().getFileName().toString().equals("pom.xml"))
                    text.append("确保 Surefire 可执行 JUnit 5，即使项目已有 JUnit 依赖。\n")
                            .append("JUnit 4 混合项目补充 Vintage 保留旧测试；已知旧于 4.12 的版本先确认升级。\n")
                            .append("关联当前 pom.xml，等待 Maven 同步完成后检查依赖是否对 IDEA 测试模块可见。\n")
                            .append("此模式不把内置 JAR 安装到 Maven 本地仓库；依赖下载使用项目已有仓库和离线设置。\n");
                else text.append("应用后请求 IDEA 重新导入；仓库或构建插件不可用时需在同步窗口处理。\n");
            } else {
                text.append("离线复制到 .unitmind/test-libs，并给 IDEA 模块添加 TEST 作用域库。\n")
                        .append("不会修改 Maven/Gradle 构建文件；后续构建工具刷新可能重置手工模块依赖。\n");
                for (File file : context.offlineJars()) text.append("  ").append(file.getName()).append('\n');
            }
            text.append("创建测试目录 src/test/java（已存在则保留）。\n").append(context.note());
            return text.toString();
        }
    }
    public record Applied(Path backup, String message) { }

    public static String resolutionMessage(Context context) {
        if (TestBuildFileEditor.unsupportedLegacyJUnit(context.legacyJUnitVersion()))
            return "项目使用 JUnit " + context.legacyJUnitVersion()
                    + "，Vintage 需要 JUnit 4.12 或以上。请先确认旧测试迁移；当前未确认项目测试配置可用。";
        if (context.dependencies().isEmpty())
            return "Maven 同步完成，JUnit 5 API/引擎及 Mockito/扩展已对模块 " + context.module().getName()
                    + " 的测试可见。请重新运行代码验证；依赖导入不代表测试已通过。";
        return "Maven 已完成同步，但模块 " + context.module().getName() + " 仍缺少以下测试依赖：\n"
                + context.dependencies().stream().map(d -> d.group() + ":" + d.artifact())
                .collect(java.util.stream.Collectors.joining("\n"))
                + "\n请检查 Maven 同步窗口中的仓库访问、离线模式、版本冲突及当前模块关联。";
    }

    public static boolean dependenciesResolved(Context context) {
        return context.dependencies().isEmpty() && !TestBuildFileEditor.unsupportedLegacyJUnit(context.legacyJUnitVersion());
    }

    public Context inspect(Project project, PsiFile source) throws IOException {
        ProjectTestEnvironment environment = new ProjectTestEnvironmentResolver().resolve(project, source);
        if (!environment.isUsable()) throw new IOException(environment.getDiagnostics().get(0).getMessage());
        Path sourcePath = ReadAction.compute(() -> Path.of(source.getVirtualFile().getPath()));
        Path boundary = project.getBasePath() == null ? null : Path.of(project.getBasePath());
        if (boundary != null && !sourcePath.startsWith(boundary)) boundary = null;
        Path build = null;
        for (Path path = sourcePath.getParent(); path != null; path = path.getParent()) {
            List<Path> found = new ArrayList<>();
            for (String name : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
                if (Files.isRegularFile(path.resolve(name))) found.add(path.resolve(name));
            }
            if (found.size() > 1) throw new IOException("当前模块包含多个构建文件，无法自动选择，请先消除 Maven/Gradle 配置歧义。");
            if (!found.isEmpty()) { build = found.get(0); break; }
            if (path.equals(boundary)) break;
        }
        if (build == null && environment.getWorkingDirectory() == null) throw new IOException("无法确定模块内容目录。");
        Path directory = build == null ? environment.getWorkingDirectory().toPath() : build.getParent();
        if (build == null) {
            String normalized = sourcePath.toString().replace('\\', '/');
            int main = normalized.indexOf("/src/main/java/");
            if (main >= 0) directory = Path.of(normalized.substring(0, main));
        }
        if (!Files.isDirectory(directory)) throw new IOException("无法确定模块目录。");
        List<File> projectLibraries = new ArrayList<>(environment.getRuntimeClasspath());
        if (environment.isBundledJunit()) projectLibraries.remove(
                new File(PathUtil.getJarPathForClass(org.junit.platform.console.ConsoleLauncher.class)));
        if (environment.hasOfflineTestLibrary()) {
            List<File> owned = ReadAction.compute(() -> {
              List<File> files = new ArrayList<>();
              for (Module module : ModuleManager.getInstance(project).getModules()) {
                for (var entry : ModuleRootManager.getInstance(module).getOrderEntries()) {
                    if (entry instanceof LibraryOrderEntry lib && OFFLINE_LIBRARY.equals(lib.getLibraryName()) && lib.getLibrary() != null) {
                        for (var jarRoot : lib.getLibrary().getFiles(com.intellij.openapi.roots.OrderRootType.CLASSES)) {
                            var jar = com.intellij.openapi.vfs.JarFileSystem.getInstance().getVirtualFileForJar(jarRoot);
                            if (jar != null) files.add(VfsUtilCore.virtualToIoFile(jar));
                        }
                    }
                }
              }
              return files;
            });
            projectLibraries.removeAll(owned);
        }
        boolean apiMissing = environment.isBundledJunit() || environment.hasOfflineTestLibrary()
                && !contains(projectLibraries, "org/junit/jupiter/api/Test.class");
        boolean junit = apiMissing || !contains(projectLibraries, "org/junit/jupiter/engine/JupiterTestEngine.class");
        boolean core = environment.isBundledMockito() || environment.hasOfflineTestLibrary()
                && !contains(projectLibraries, "org/mockito/Mockito.class");
        boolean extension = core || !contains(projectLibraries, "org/mockito/junit/jupiter/MockitoExtension.class");
        int feature = BundledMockitoRuntime.javaFeature(environment.getJavaExecutable());
        String mockitoVersion = version(projectLibraries, "mockito-core-", feature < 11 ? "4.11.0" : "5.18.0");
        String junitVersion = version(projectLibraries, "junit-jupiter-api-", "5.12.0");
        boolean legacyJUnit = contains(projectLibraries, "org/junit/Test.class")
                || contains(projectLibraries, "junit/framework/TestCase.class");
        String legacyVersion = "";
        if (legacyJUnit) for (File file : projectLibraries) {
            var match = java.util.regex.Pattern.compile("junit-(?:dep-)?(\\d+(?:\\.\\d+)+).*\\.jar").matcher(file.getName());
            if (match.matches()) { legacyVersion = match.group(1); break; }
        }
        List<TestBuildFileEditor.Dependency> dependencies = new ArrayList<>();
        if (junit) dependencies.add(new TestBuildFileEditor.Dependency("org.junit.jupiter",
                apiMissing ? "junit-jupiter" : "junit-jupiter-engine", junitVersion));
        if (legacyJUnit && !contains(projectLibraries, "org/junit/vintage/engine/VintageTestEngine.class"))
            dependencies.add(new TestBuildFileEditor.Dependency("org.junit.vintage", "junit-vintage-engine", junitVersion));
        if (core) dependencies.add(new TestBuildFileEditor.Dependency("org.mockito", "mockito-core", mockitoVersion));
        if (extension) dependencies.add(new TestBuildFileEditor.Dependency("org.mockito", "mockito-junit-jupiter", mockitoVersion));
        List<File> offline = new ArrayList<>();
        String note = "已有依赖和声明的版本将保留；应用不会运行测试或调用大模型。";
        if (apiMissing) offline.add(new File(PathUtil.getJarPathForClass(org.junit.platform.console.ConsoleLauncher.class)));
        else if (junit) note += "\n项目已有 JUnit API；缺少的引擎请通过项目配置模式补齐，离线模式保留现有 JUnit 版本。";
        if (core) offline.addAll(BundledMockitoRuntime.libraries(feature));
        else if (extension) {
            if (mockitoVersion.equals("4.11.0") || mockitoVersion.equals("5.18.0")) {
                offline.addAll(BundledMockitoRuntime.libraries(mockitoVersion.startsWith("4.") ? 8 : 17).stream()
                        .filter(file -> file.getName().startsWith("mockito-junit-jupiter-")).toList());
            } else note += "\n已有 Mockito " + mockitoVersion + "，离线包不包含同版扩展；请选项目配置模式补齐扩展。";
        }
        String before = build == null ? "" : text(build);
        String disk = build == null ? "" : com.casc.unittestagent.util.ProjectFileText.diskHash(build);
        var buildVirtual = build == null ? null : LocalFileSystem.getInstance().refreshAndFindFileByNioFile(build);
        var charset = buildVirtual == null ? StandardCharsets.UTF_8 : ReadAction.compute(buildVirtual::getCharset);
        String separator = buildVirtual == null ? "\n" : ReadAction.compute(() -> {
            // Load the file once so its detected CRLF/CR separator is available, rather than
            // falling back to the host/project default before a document has been opened.
            FileDocumentManager.getInstance().getDocument(buildVirtual);
            return FileDocumentManager.getInstance().getLineSeparator(buildVirtual, project);
        });
        Path refreshRoot = directory;
        if (build != null && !build.getFileName().toString().equals("pom.xml")) {
            for (Path path = directory; path != null; path = path.getParent()) {
                if (Files.isRegularFile(path.resolve("settings.gradle")) || Files.isRegularFile(path.resolve("settings.gradle.kts"))) {
                    refreshRoot = path; break;
                }
                if (path.equals(boundary)) break;
            }
        }
        return new Context(project, environment.getModule(), directory, build, before, disk, refreshRoot,
                List.copyOf(dependencies), List.copyOf(offline), junit, note, charset, separator, legacyVersion);
    }

    public Plan plan(Context context, Mode mode) {
        if (mode == Mode.IDEA_OFFLINE) return new Plan(context, mode, context.before());
        if (context.buildFile() == null) throw new IllegalArgumentException("没有 Maven/Gradle 构建文件，请使用 IDEA 离线模式。");
        if (TestBuildFileEditor.unsupportedLegacyJUnit(context.legacyJUnitVersion()))
            throw new IllegalArgumentException("已有 JUnit " + context.legacyJUnitVersion()
                    + " 不能直接使用 Vintage。请先确认升级到 JUnit 4.12 或以上，或选择仅 IDEA 离线模式。");
        String name = context.buildFile().getFileName().toString();
        String after = name.equals("pom.xml")
                ? TestBuildFileEditor.maven(context.project(), context.before(), context.dependencies(), true)
                : TestBuildFileEditor.gradle(context.before(), context.dependencies(), true, name.endsWith(".kts"));
        return new Plan(context, mode, after);
    }

    /** Must run on EDT: one write command for document changes and module configuration. */
    public Applied apply(Plan plan) throws IOException {
        Context context = plan.context();
        if (context.project().isDisposed() || context.module().isDisposed()) throw new IOException("项目或模块已关闭。");
        Path managed = context.directory().resolve(".unitmind");
        rejectSymlink(managed);
        Path libs = managed.resolve("test-libs");
        rejectSymlink(libs);
        if (plan.mode() == Mode.PROJECT && (!text(context.buildFile()).equals(context.before())
                || !com.casc.unittestagent.util.ProjectFileText.diskHash(context.buildFile()).equals(context.diskBeforeHash()))) {
            throw new IOException("构建文件在预览后已被修改，请重新打开依赖配置按钮。");
        }
        if (plan.mode() == Mode.PROJECT && !context.buildCharset().newEncoder().canEncode(plan.after())) {
            throw new IOException("构建文件编码无法表示当前配置，请在 IDEA 中调整编码后重新预览。");
        }
        if (plan.mode() == Mode.IDEA_OFFLINE) for (File source : context.offlineJars()) {
            Path target = libs.resolve(source.getName());
            rejectSymlink(target);
            if (Files.exists(target) && Files.mismatch(source.toPath(), target) != -1) {
                throw new IOException("离线依赖目录中存在同名不同内容的 JAR，已停止导入：" + source.getName());
            }
        }
        Path backup = null;
        if (plan.mode() == Mode.PROJECT && !context.before().equals(plan.after())) {
            Path backups = managed.resolve("backups"); rejectSymlink(backups); Files.createDirectories(backups);
            backup = Files.createTempDirectory(backups, "dependencies-").resolve(context.buildFile().getFileName());
            Files.writeString(backup, com.intellij.openapi.util.text.StringUtil.convertLineSeparators(
                    context.before(), context.lineSeparator()), context.buildCharset());
        }
        Path testPath = context.directory().resolve("src/test/java");
        rejectSymlink(context.directory().resolve("src"));
        rejectSymlink(context.directory().resolve("src/test"));
        rejectSymlink(testPath);
        List<File> copied = new ArrayList<>();
        if (plan.mode() == Mode.IDEA_OFFLINE && !context.offlineJars().isEmpty()) {
            Files.createDirectories(libs);
            for (File file : context.offlineJars()) {
                Path target = libs.resolve(file.getName());
                if (!Files.exists(target)) Files.copy(file.toPath(), target);
                copied.add(target.toFile());
            }
        }
        Files.createDirectories(testPath);
        var testRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(testPath);
        if (testRoot == null) throw new IOException("无法刷新测试源码目录。");
        RuntimeException[] failure = new RuntimeException[1];
        WriteCommandAction.runWriteCommandAction(context.project(), "UnitMind：配置测试依赖", null, () -> {
            try {
                if (plan.mode() == Mode.PROJECT && !context.before().equals(plan.after())) {
                    var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(context.buildFile());
                    if (file == null) throw new IllegalStateException("构建文件不可用。");
                    var document = FileDocumentManager.getInstance().getDocument(file);
                    if (document == null || !document.getText().equals(context.before())) throw new IllegalStateException("构建文档已改变，请重新预览。");
                    document.setText(plan.after());
                    PsiDocumentManager.getInstance(context.project()).commitDocument(document);
                    FileDocumentManager.getInstance().saveDocument(document);
                    file.setCharset(context.buildCharset());
                    VfsUtil.saveText(file, com.intellij.openapi.util.text.StringUtil.convertLineSeparators(plan.after(), context.lineSeparator()));
                    file.setCharset(context.buildCharset());
                    if (!com.casc.unittestagent.util.ProjectFileText.read(context.buildFile()).equals(plan.after())) {
                        throw new IOException("构建文件保存后与预览文本不一致。");
                    }
                }
                if (plan.mode() == Mode.PROJECT) {
                    for (Module module : new java.util.LinkedHashSet<>(List.of(context.module(), testModule(context.module())))) {
                        ModuleRootModificationUtil.updateModel(module, model -> {
                            var library = model.getModuleLibraryTable().getLibraryByName(OFFLINE_LIBRARY);
                            if (library != null) {
                                model.getModuleLibraryTable().removeLibrary(library);
                            }
                        });
                    }
                }
                if (plan.mode() == Mode.IDEA_OFFLINE) {
                    Module module = testModule(context.module());
                    ModuleRootModificationUtil.updateModel(module, model -> {
                        var entry = Arrays.stream(model.getContentEntries())
                                .filter(item -> testRoot.getUrl().equals(item.getUrl()) || testRoot.getUrl().startsWith(item.getUrl() + "/"))
                                .findFirst().orElseGet(() -> model.addContentEntry(testRoot));
                        var existing = Arrays.stream(entry.getSourceFolders()).filter(folder -> folder.getUrl().equals(testRoot.getUrl())).findFirst().orElse(null);
                        if (existing == null) {
                            entry.addSourceFolder(testRoot, true);
                        } else if (!existing.isTestSource()) {
                            String prefix = existing.getPackagePrefix();
                            entry.removeSourceFolder(existing);
                            entry.addSourceFolder(testRoot, true).setPackagePrefix(prefix);
                        }
                        // An empty marker also records an explicit offline choice when all JARs already exist.
                        {
                            var library = model.getModuleLibraryTable().getLibraryByName(OFFLINE_LIBRARY);
                            if (library == null) library = model.getModuleLibraryTable().createLibrary(OFFLINE_LIBRARY);
                            var libModel = library.getModifiableModel();
                            for (File jar : copied) {
                                String url = com.intellij.openapi.vfs.JarFileSystem.PROTOCOL_PREFIX
                                        + jar.getAbsolutePath().replace('\\', '/') + "!/";
                                if (!Arrays.asList(libModel.getUrls(com.intellij.openapi.roots.OrderRootType.CLASSES)).contains(url))
                                    libModel.addRoot(url, com.intellij.openapi.roots.OrderRootType.CLASSES);
                            }
                            libModel.commit();
                            for (var item : model.getOrderEntries()) if (item instanceof LibraryOrderEntry lib && OFFLINE_LIBRARY.equals(lib.getLibraryName())) {
                                lib.setScope(DependencyScope.TEST);
                                lib.setExported(false);
                            }
                        }
                    });
                }
            } catch (IOException ex) { failure[0] = new IllegalStateException(ex.getMessage(), ex); }
            catch (RuntimeException ex) { failure[0] = ex; }
        });
        if (failure[0] != null) throw new IOException("依赖配置未能完成：" + failure[0].getMessage(), failure[0]);
        return new Applied(backup, plan.mode() == Mode.PROJECT ? "测试依赖配置已写入，等待项目重新导入。"
                : "离线测试依赖已以 TEST 作用域导入 IDEA；Maven/Gradle 或 CI 请使用项目配置模式。");
    }

    private static Module testModule(Module source) {
        String name = source.getName();
        if (name.endsWith(".main")) {
            String target = name.substring(0, name.length() - 5) + ".test";
            for (Module module : ModuleManager.getInstance(source.getProject()).getModules()) if (module.getName().equals(target)) return module;
        }
        return source;
    }
    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("自动配置目标是符号链接，请使用普通模块目录：" + path.getFileName());
    }
    private static String text(Path path) throws IOException {
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        String cached = ReadAction.compute(() -> {
            var document = file == null ? null : FileDocumentManager.getInstance().getCachedDocument(file);
            return document == null ? null : document.getText();
        });
        return cached == null ? com.casc.unittestagent.util.ProjectFileText.read(path) : cached;
    }
    private static boolean contains(List<File> classpath, String name) {
        for (File file : classpath) {
            if (file.isDirectory() && new File(file, name).isFile()) return true;
            if (file.isFile() && file.getName().endsWith(".jar")) try (ZipFile zip = new ZipFile(file)) {
                if (zip.getEntry(name) != null) return true;
            } catch (IOException ignored) { }
        }
        return false;
    }
    private static String version(List<File> classpath, String prefix, String fallback) {
        for (File file : classpath) {
            String name = file.getName();
            if (name.startsWith(prefix) && name.endsWith(".jar")) {
                String version = name.substring(prefix.length(), name.length() - 4);
                if (version.matches("[0-9][A-Za-z0-9_.-]*")) return version;
            }
        }
        return fallback;
    }
}
