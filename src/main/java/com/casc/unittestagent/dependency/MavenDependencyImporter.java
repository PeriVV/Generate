package com.casc.unittestagent.dependency;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.nio.file.Path;
import java.util.List;

/** Register the selected POM, reread it, resolve dependencies, and wait for IDEA's module import. */
public final class MavenDependencyImporter {
    interface Backend {
        void register(Path pom);
        Promise<?> read();
        Promise<?> resolve();
        Promise<?> imported();
    }

    private final Backend backend;

    public MavenDependencyImporter(Project project) {
        this(new Backend() {
            private VirtualFile target;
            private MavenProjectsManager manager;
            private void checkProject() {
                if (project.isDisposed()) throw new IllegalStateException("项目已关闭，Maven 导入已停止。");
            }
            @Override public void register(Path pom) {
                checkProject();
                target = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom);
                if (target == null) throw new IllegalStateException("找不到待导入的 pom.xml：" + pom);
                manager = MavenProjectsManager.getInstance(project);
                manager.addManagedFilesOrUnignore(List.of(target));
            }
            @Override public Promise<?> read() {
                checkProject();
                var selected = manager.findProject(target);
                // Empty means all managed roots, including a newly registered POM not yet read.
                return manager.forceUpdateProjects(selected == null ? List.of() : List.of(selected));
            }
            @Override public Promise<?> resolve() {
                checkProject();
                var selected = manager.findProject(target);
                if (selected == null || manager.isIgnored(selected))
                    throw new IllegalStateException("所选 pom.xml 未被 Maven 正常识别或仍被忽略，请查看 Maven 同步窗口。");
                // New IDEs implement this legacy API synchronously; never invoke it on the EDT.
                return background(() -> manager.scheduleImportAndResolve());
            }
            @Override public Promise<?> imported() {
                checkProject();
                return background(() -> awaitLegacyImport(manager));
            }
        });
    }

    MavenDependencyImporter(Backend backend) { this.backend = backend; }

    public Promise<Void> importPom(Path pom) {
        try {
            backend.register(pom);
            return completion(backend.read())
                    .thenAsync(ignored -> completion(backend.resolve()))
                    .thenAsync(ignored -> completion(backend.imported()));
        } catch (RuntimeException error) {
            var rejected = new AsyncPromise<Void>();
            rejected.setError(error);
            return rejected;
        }
    }

    private static Promise<Void> completion(Promise<?> stage) { return stage.then(ignored -> null); }

    private static Promise<Void> background(java.util.function.Supplier<Promise<?>> action) {
        var result = new AsyncPromise<Void>();
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try { completion(action.get()).processed(result); }
            catch (RuntimeException error) { result.setError(error); }
        });
        return result;
    }

    /** IDEA 222 resolves before module import; newer IDEs complete both in scheduleImportAndResolve. */
    static Promise<?> awaitLegacyImport(Object manager) {
        try {
            Object value = manager.getClass().getMethod("waitForImportCompletion").invoke(manager);
            if (value instanceof Promise<?> promise) return promise;
            throw new IllegalStateException("无法确认 Maven 模块导入完成。");
        } catch (NoSuchMethodException modernIde) {
            var completed = new AsyncPromise<Void>();
            completed.setResult(null);
            return completed;
        } catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("等待 Maven 模块导入失败。", error.getCause());
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("无法确认 Maven 模块导入完成。", error);
        }
    }
}
