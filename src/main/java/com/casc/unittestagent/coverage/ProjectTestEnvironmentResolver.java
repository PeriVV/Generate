package com.casc.unittestagent.coverage;

import com.intellij.openapi.application.ApplicationManager;
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

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
        return ApplicationManager.getApplication().runReadAction(
                (Computable<ProjectTestEnvironment>) () -> resolveInReadAction(project, sourceFile, environment));
    }

    private ProjectTestEnvironment resolveInReadAction(Project project, PsiFile sourceFile,
                                                        ProjectTestEnvironment environment) {
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
        if (!hasClass(facade, modules, "org.junit.jupiter.api.Test")) {
            environment.error("JUNIT5_NOT_FOUND", "当前模块测试 classpath 中未找到 JUnit 5。");
        }
        if (!hasClass(facade, modules, "org.mockito.Mockito")) {
            environment.error("MOCKITO_NOT_FOUND", "当前模块测试 classpath 中未找到 Mockito。");
        }
        if (!environment.diagnostics.isEmpty()) return environment;
        for (Module classpathModule : modules) {
            ModuleRootManager roots = ModuleRootManager.getInstance(classpathModule);
            addRoots(environment.classpath, roots.orderEntries().recursively().classes().getRoots());
            addRoots(environment.sourcePath, roots.getSourceRoots(true));
            CompilerModuleExtension extension = CompilerModuleExtension.getInstance(classpathModule);
            if (extension != null) {
                addRoot(environment.classpath, extension.getCompilerOutputPath());
                addRoot(environment.classpath, extension.getCompilerOutputPathForTests());
            }
        }
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
