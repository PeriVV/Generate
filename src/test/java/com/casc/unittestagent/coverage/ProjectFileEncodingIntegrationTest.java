package com.casc.unittestagent.coverage;

import com.casc.unittestagent.util.ProjectFileText;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class ProjectFileEncodingIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    public void testGbkTransitiveSourcesAreCopiedAsUtf8AndExecutedWithoutChangingProjectFiles() throws Exception {
        Path root = Files.createTempDirectory("unitmind 中文 GBK 编译-");
        try {
            var gbk = Charset.forName("GBK");
            Path project = Files.createDirectories(root.resolve("project/sample"));
            String helper = "package sample; class Helper { String text() { return \"中文\" + new Tail().text(); } }\r\n";
            String tail = "package sample; class Tail { String text() { return \"尾\"; } }\r\n";
            for (String name : new String[]{"Helper", "Tail"}) {
                Path file = project.resolve(name + ".java");
                Files.writeString(file, name.equals("Helper") ? helper : tail, gbk);
                var virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
                virtual.setCharset(gbk);
            }
            Path sandbox = Files.createDirectories(root.resolve("sandbox"));
            Path subject = Files.createDirectories(sandbox.resolve("sample")).resolve("Subject.java");
            Files.writeString(subject, "package sample; public class Subject { public static void main(String[] args) { System.out.print(new Helper().text()); } }");
            Path classes = Files.createDirectories(root.resolve("classes"));
            var env = new ProjectTestEnvironment();
            env.javaExecutable = Path.of(System.getProperty("java.home"), "bin", BuildConfigurationProcess.isWindows() ? "java.exe" : "java").toString();
            env.sourcePath.add(project.getParent().toFile()); env.languageFeature = 17;
            var diagnostic = new TestSandboxCompiler().compileTargetWithModuleJdk(env, sandbox, classes, subject, "sample.Subject");
            assertNull(diagnostic == null ? "" : diagnostic.getMessage(), diagnostic);
            assertTrue(Files.isRegularFile(classes.resolve("sample/Tail.class")));
            assertTrue(Files.readString(sandbox.resolve("sample/Helper.java")).contains("中文"));
            assertEquals(helper, Files.readString(project.resolve("Helper.java"), gbk));
            var run = LongCommandLineLauncher.start(new ProcessBuilder(env.javaExecutable, "-Dfile.encoding=UTF-8", "-cp", classes.toString(), "sample.Subject")
                    .directory(root.toFile()).redirectErrorStream(true), root);
            assertTrue(run.waitFor(30, TimeUnit.SECONDS));
            String output = new String(run.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(output, 0, run.exitValue()); assertEquals("中文尾", output);
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }

    public void testBomCrLfAndExternalChangesUseNormalizedTextButDifferentDiskFingerprints() throws Exception {
        Path root = Files.createTempDirectory("unitmind-file-encoding-");
        try {
            Path file = root.resolve("Subject.java");
            Files.writeString(file, "\ufeffclass Subject {}\r\n");
            assertEquals("class Subject {}\n", ProjectFileText.read(file));
            String before = ProjectFileText.diskHash(file);
            Files.writeString(file, "class Subject {}\n");
            assertEquals("class Subject {}\n", ProjectFileText.read(file));
            assertFalse(before.equals(ProjectFileText.diskHash(file)));
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }
}
