package com.casc.unittestagent.coverage;

import com.intellij.compiler.CompilerConfiguration;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.jps.model.java.compiler.AnnotationProcessingConfiguration;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 解析模块 JDK、源码根、依赖和编译输出，供编译验证与覆盖率运行共用。 */
public class ProjectTestEnvironmentResolver {
    public ProjectTestEnvironment resolve(Project project, PsiFile sourceFile) {
        ProjectTestEnvironment environment = new ProjectTestEnvironment();
        if (project == null || sourceFile == null || sourceFile.getVirtualFile() == null) {
            environment.error("PROJECT_CONTEXT_MISSING", "缺少项目或源文件上下文，无法执行测试。");
            return environment;
        }
        if (DumbService.isDumb(project)) {
            environment.error("INDEX_NOT_READY", "IntelliJ 项目索引尚未完成，请等待索引完成后重试。");
            return environment;
        }
        ProjectTestEnvironment resolved = ApplicationManager.getApplication().runReadAction(
                (Computable<ProjectTestEnvironment>) () -> resolveInReadAction(project, sourceFile, environment));
        if (resolved.isUsable() && resolved.bundledMockito) attachBundledMockito(resolved, resolved.javaExecutable);
        return resolved;
    }

    /** 只在运行阶段解析构建工具配置，避免在 IDE 读锁中等待外部进程。 */
    public ProjectTestEnvironment resolveForExecution(Project project, PsiFile sourceFile,
                                                     java.util.function.BooleanSupplier cancelled) {
        ProjectTestEnvironment environment = resolve(project, sourceFile);
        if (!environment.isUsable()) return environment;
        try {
            java.nio.file.Path source = ApplicationManager.getApplication().runReadAction(
                    (Computable<java.nio.file.Path>) () -> java.nio.file.Path.of(sourceFile.getVirtualFile().getPath()));
            environment.runtimeConfiguration = new ProjectTestRuntimeConfigurationResolver().resolve(source,
                    project.getBasePath() == null ? null : java.nio.file.Path.of(project.getBasePath()),
                    environment.javaExecutable, environment.workingDirectory, cancelled,
                    environment.bundledJunit || environment.offlineTestLibrary, project);
            if (environment.bundledMockito) {
                String testJava = environment.runtimeConfiguration.getJavaExecutable();
                attachBundledMockito(environment, testJava.isBlank() ? environment.javaExecutable : testJava);
                if (!environment.isUsable()) return environment;
                List<String> args = new ArrayList<>(environment.runtimeConfiguration.getJvmArguments());
                File core = environment.bundledMockitoLibraries.stream()
                        .filter(file -> file.getName().startsWith("mockito-core-5.")).findFirst().orElse(null);
                if (core != null && !hasMockitoAgent(args)) args.add("-javaagent:" + core.getAbsolutePath());
                environment.runtimeConfiguration = environment.runtimeConfiguration.withJvmArguments(args).withOrigin(
                        environment.runtimeConfiguration.getOrigin() + "；内置 " + environment.bundledMockitoVersion);
            }
            if (environment.offlineMockitoAgent != null && !hasMockitoAgent(environment.runtimeConfiguration.getJvmArguments())) {
                List<String> args = new ArrayList<>(environment.runtimeConfiguration.getJvmArguments());
                args.add("-javaagent:" + environment.offlineMockitoAgent.getAbsolutePath());
                environment.runtimeConfiguration = environment.runtimeConfiguration.withJvmArguments(args).withOrigin(
                        environment.runtimeConfiguration.getOrigin() + "；IDEA 离线 Mockito");
            }
        } catch (com.intellij.openapi.progress.ProcessCanceledException ex) {
            throw ex;
        } catch (Exception ex) {
            environment.error("RUNTIME_CONFIGURATION_UNAVAILABLE", "项目测试运行配置无法复用："
                    + safeConfigurationMessage(ex));
        }
        return environment;
    }

    private static String safeConfigurationMessage(Exception ex) {
        // XML/JSON 库错误可能包含配置值，不把它们放进日志或模型上下文。
        if (ex instanceof java.io.IOException || ex instanceof IllegalArgumentException
                && !(ex instanceof org.json.JSONException)) return ex.getMessage();
        return "配置解析失败，请检查构建工具和 .unitmind/test-runtime.json。";
    }

    private void attachBundledMockito(ProjectTestEnvironment environment, String testJava) {
        try {
            int feature = Math.min(BundledMockitoRuntime.javaFeature(environment.javaExecutable),
                    BundledMockitoRuntime.javaFeature(testJava));
            List<File> libraries = BundledMockitoRuntime.libraries(feature);
            environment.classpath.removeAll(environment.bundledMockitoLibraries);
            environment.bundledMockitoLibraries.clear();
            environment.bundledMockitoLibraries.addAll(libraries);
            LinkedHashSet<File> classpath = new LinkedHashSet<>(libraries);
            classpath.addAll(environment.classpath);
            environment.classpath.clear();
            environment.classpath.addAll(classpath);
            environment.bundledMockitoVersion = feature < 11 ? "Mockito 4.11.0" : "Mockito 5.18.0";
        } catch (java.io.IOException ex) {
            environment.error("BUNDLED_MOCKITO_UNAVAILABLE", "内置 Mockito 无法就绪：" + ex.getMessage());
        }
    }

    private static boolean hasMockitoAgent(List<String> arguments) {
        for (String argument : arguments) {
            if (!argument.startsWith("-javaagent:")) continue;
            String path = argument.substring("-javaagent:".length()).split("=", 2)[0];
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(path)) {
                if (jar.getManifest() != null && "org.mockito.internal.PremainAttach".equals(
                        jar.getManifest().getMainAttributes().getValue("Premain-Class"))) return true;
            } catch (java.io.IOException ignored) { }
        }
        return false;
    }

    private ProjectTestEnvironment resolveInReadAction(Project project, PsiFile sourceFile,
                                                        ProjectTestEnvironment environment) {
        environment.sourceFilePath = sourceFile.getVirtualFile().getPath();
        Module module = ProjectFileIndex.getInstance(project).getModuleForFile(sourceFile.getVirtualFile());
        if (module == null) {
            environment.error("MODULE_NOT_FOUND", "源文件不属于任何已导入的 IntelliJ 模块。");
            return environment;
        }
        Sdk sdk = ModuleRootManager.getInstance(module).getSdk();
        if (sdk == null || sdk.getHomePath() == null) {
            environment.error("SDK_NOT_CONFIGURED", "当前模块未配置 Java SDK。");
            return environment;
        }
        File java = new File(sdk.getHomePath(), "bin" + File.separator
                + (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java"));
        if (!java.isFile()) {
            environment.error("JAVA_EXECUTABLE_NOT_FOUND", "模块 JDK 中未找到 java 可执行文件。");
            return environment;
        }

        environment.project = project;
        environment.module = module;
        environment.javaExecutable = java.getAbsolutePath();
        VirtualFile contentRoot = ProjectFileIndex.getInstance(project)
                .getContentRootForFile(sourceFile.getVirtualFile());
        if (contentRoot != null && contentRoot.isInLocalFileSystem()) {
            environment.workingDirectory = VfsUtilCore.virtualToIoFile(contentRoot);
        } else if (project.getBasePath() != null) {
            environment.workingDirectory = new File(project.getBasePath());
        }
        environment.sourceFileName = sourceFile.getName();
        environment.sourceText = sourceFile.getText();
        environment.sourcePackage = sourceFile instanceof PsiJavaFile ? ((PsiJavaFile) sourceFile).getPackageName() : "";
        LanguageLevel languageLevel = PsiUtil.getLanguageLevel(sourceFile);
        environment.languageFeature = languageLevel.toJavaVersion().feature;
        environment.previewLanguage = languageLevel.isPreview();

        List<Module> modules = testClasspathModules(project, module);
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        environment.bundledJunit = !hasClass(facade, modules, "org.junit.jupiter.api.Test");
        environment.bundledMockito = !hasClass(facade, modules, "org.mockito.Mockito");
        if (!environment.diagnostics.isEmpty()) return environment;
        for (Module classpathModule : modules) {
            ModuleRootManager roots = ModuleRootManager.getInstance(classpathModule);
            for (var entry : roots.getOrderEntries()) {
                if (entry instanceof com.intellij.openapi.roots.LibraryOrderEntry lib
                        && "UnitMind offline test runtime".equals(lib.getLibraryName()) && lib.getLibrary() != null) {
                    environment.offlineTestLibrary = true;
                    for (VirtualFile jarRoot : lib.getLibrary().getFiles(OrderRootType.CLASSES)) {
                        VirtualFile jar = JarFileSystem.getInstance().getVirtualFileForJar(jarRoot);
                        if (jar != null && jar.getName().startsWith("mockito-core-5.")) {
                            environment.offlineMockitoAgent = VfsUtilCore.virtualToIoFile(jar);
                        }
                    }
                }
            }
            addRoots(environment.classpath, roots.orderEntries().recursively().classes().getRoots());
            addRoots(environment.sourcePath, roots.getSourceRoots(true));
            CompilerModuleExtension extension = CompilerModuleExtension.getInstance(classpathModule);
            if (extension != null) {
                addRoot(environment.classpath, extension.getCompilerOutputPath());
                addRoot(environment.classpath, extension.getCompilerOutputPathForTests());
            }
        }
        // IntelliJ can resolve classes from a dependent module's source tree even when that
        // module has not been compiled yet. javac cannot: a module output directory that is
        // empty does not help it locate those sources. Add production source roots (including
        // generated source roots) and outputs for every transitive module dependency so the
        // temporary compile behaves like the IDE project model in a clean multi-module checkout.
        List<Module> dependencyModules = transitiveDependencies(modules);
        for (Module dependencyModule : dependencyModules) {
            ModuleRootManager roots = ModuleRootManager.getInstance(dependencyModule);
            addRoots(environment.sourcePath, roots.getSourceRoots(false));
            CompilerModuleExtension extension = CompilerModuleExtension.getInstance(dependencyModule);
            if (extension != null) {
                addRoot(environment.classpath, extension.getCompilerOutputPath());
            }
        }
        List<Module> compilationModules = new ArrayList<>(modules);
        compilationModules.addAll(dependencyModules);
        addAnnotationProcessing(environment, project, compilationModules);
        addRoots(environment.platformClasspath, sdk.getRootProvider().getFiles(OrderRootType.CLASSES));
        for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots(false)) {
            if (root.findChild("module-info.java") != null) {
                environment.jpms = true;
                break;
            }
        }
        if (environment.jpms) {
            environment.modulePath.addAll(environment.classpath);
            environment.classpath.clear();
        }
        if (environment.bundledJunit) {
            // 仅补齐测试框架，业务依赖、SDK、处理器仍由当前模块提供。
            String runtime = com.intellij.util.PathUtil.getJarPathForClass(org.junit.platform.console.ConsoleLauncher.class);
            if (runtime == null || !new File(runtime).isFile()) {
                environment.error("BUNDLED_JUNIT_NOT_FOUND", "无法定位插件内置 JUnit 运行时，请重新安装插件。");
            } else {
                environment.classpath.add(new File(runtime));
            }
        }
        return environment;
    }

    private List<Module> testClasspathModules(Project project, Module sourceModule) {
        List<Module> result = new ArrayList<>();
        result.add(sourceModule);
        for (Module candidate : ModuleManager.getInstance(project).getModules()) {
            if (candidate != sourceModule && moduleDependsOn(candidate, sourceModule)
                    && (hasTestSourceRoot(project, candidate) || looksLikeTestModule(candidate))) {
                result.add(candidate);
            }
        }
        return result;
    }

    private void addAnnotationProcessing(ProjectTestEnvironment environment,
                                         Project project,
                                         List<Module> modules) {
        CompilerConfiguration compilerConfiguration = CompilerConfiguration.getInstance(project);
        PathMacroManager macros = PathMacroManager.getInstance(project);
        for (Module module : modules) {
            AnnotationProcessingConfiguration configuration =
                    compilerConfiguration.getAnnotationProcessingConfiguration(module);
            if (configuration == null || !configuration.isEnabled()) continue;
            environment.processorsFromClasspath |= configuration.isObtainProcessorsFromClasspath();
            environment.processorModulePath |= configuration.isUseProcessorModulePath();
            environment.annotationProcessors.addAll(configuration.getProcessors());
            environment.annotationProcessorOptions.putAll(configuration.getProcessorOptions());
            String processorPath = configuration.getProcessorPath();
            if (processorPath == null || processorPath.isBlank()) continue;
            for (String entry : processorPath.split(Pattern.quote(File.pathSeparator))) {
                if (entry == null || entry.isBlank()) continue;
                File processor = new File(macros.expandPath(entry));
                if (processor.exists()) environment.annotationProcessorPath.add(processor);
            }
        }
    }

    private List<Module> transitiveDependencies(List<Module> roots) {
        Set<Module> rootSet = new LinkedHashSet<>(roots);
        Set<Module> visited = new LinkedHashSet<>(rootSet);
        Deque<Module> pending = new ArrayDeque<>(roots);
        List<Module> result = new ArrayList<>();
        while (!pending.isEmpty()) {
            Module current = pending.removeFirst();
            for (Module dependency : ModuleRootManager.getInstance(current).getDependencies()) {
                if (!visited.add(dependency)) continue;
                pending.addLast(dependency);
                if (!rootSet.contains(dependency)) result.add(dependency);
            }
        }
        return result;
    }

    private boolean moduleDependsOn(Module candidate, Module sourceModule) {
        Set<Module> visited = new LinkedHashSet<>();
        Deque<Module> pending = new ArrayDeque<>();
        pending.add(candidate);
        while (!pending.isEmpty()) {
            Module current = pending.removeFirst();
            if (!visited.add(current)) continue;
            for (Module dependency : ModuleRootManager.getInstance(current).getDependencies()) {
                if (dependency == sourceModule) return true;
                pending.addLast(dependency);
            }
        }
        return false;
    }

    private boolean hasTestSourceRoot(Project project, Module module) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots(true)) {
            if (index.isInTestSourceContent(root)) return true;
        }
        return false;
    }

    private boolean looksLikeTestModule(Module module) {
        String name = module.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".test") || name.endsWith("-test") || name.endsWith("_test") || name.endsWith(":test");
    }

    private boolean hasClass(JavaPsiFacade facade, List<Module> modules, String qualifiedName) {
        String classFile = qualifiedName.replace('.', '/') + ".class";
        for (Module module : modules) {
            GlobalSearchScope scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, true);
            if (facade.findClass(qualifiedName, scope) != null) return true;
            for (VirtualFile root : ModuleRootManager.getInstance(module)
                    .orderEntries().recursively().classes().getRoots()) {
                if (root.findFileByRelativePath(classFile) != null) return true;
            }
        }
        return false;
    }

    private void addRoots(Set<File> target, VirtualFile[] roots) {
        if (roots == null) return;
        for (VirtualFile root : roots) addRoot(target, root);
    }

    private void addRoot(Set<File> target, VirtualFile root) {
        if (root == null) return;
        VirtualFile local = root.getFileSystem() instanceof JarFileSystem
                ? JarFileSystem.getInstance().getVirtualFileForJar(root) : root;
        if (local != null && local.isInLocalFileSystem()) target.add(VfsUtilCore.virtualToIoFile(local));
    }
}
