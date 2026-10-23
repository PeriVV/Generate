package com.casc.unittestagent.coverage;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class ExecutionEnvironmentSessionTest extends LightJavaCodeInsightFixtureTestCase {
    public void testTaskReusesExportButRebuildsItAfterConfigurationChanges() throws Exception {
        var root = Files.createTempDirectory("session 中文 ");
        var source = myFixture.addFileToProject("sample/Subject.java", "package sample; class Subject {}");
        var calls = new AtomicInteger();
        var resolver = new ProjectTestEnvironmentResolver() {
            @Override public ProjectTestEnvironment resolveForExecution(com.intellij.openapi.project.Project project,
                    com.intellij.psi.PsiFile file, BooleanSupplier cancelled) {
                calls.incrementAndGet();
                var environment = new ProjectTestEnvironment();
                environment.project = project; environment.workingDirectory = root.toFile(); environment.sourceText = file.getText();
                return environment;
            }
        };
        try {
            var session = new ExecutionEnvironmentSession(resolver);
            var first = session.resolve(getProject(), source, () -> false);
            assertSame(first, session.resolve(getProject(), source, () -> false));
            assertEquals(1, calls.get());
            Files.writeString(root.resolve("pom.xml"), "<project/>");
            assertNotSame(first, session.resolve(getProject(), source, () -> false));
            assertEquals(2, calls.get());
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }

    public void testCancelledTaskCannotConsumeCachedEnvironment() {
        var source = myFixture.addFileToProject("sample/Other.java", "package sample; class Other {}");
        var session = new ExecutionEnvironmentSession(new ProjectTestEnvironmentResolver());
        try {
            session.resolve(getProject(), source, () -> true);
            fail("cancelled requests must stop before environment access");
        } catch (com.intellij.openapi.progress.ProcessCanceledException expected) { }
    }
}
