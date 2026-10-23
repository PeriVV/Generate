package com.casc.unittestagent.coverage;

import junit.framework.TestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Native cmd.exe contract; windowsCompatibilityTest requires a real Windows host. */
public class WindowsBatchExecutionTest extends TestCase {
    public void testBatchLaunchPreservesUnicodeSpacesMetacharactersAndLiteralPercent() throws Exception {
        if (!BuildConfigurationProcess.isWindows()) return;
        Path root = Files.createTempDirectory("unitmind batch ");
        try {
            Path folder = Files.createDirectories(root.resolve("中文 tool (space) & special!"));
            Path classes = Files.createDirectories(folder.resolve("classes"));
            Path source = folder.resolve("Recorder.java");
            Files.writeString(source, "class Recorder { public static void main(String[] args) throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), args[1]); } }");
            LocalCoverageClosureSupport.compile(source, classes, List.of());
            Path batch = folder.resolve("gradlew.bat");
            Files.writeString(batch, "@echo off\r\n\"%JAVA_HOME%\\bin\\java.exe\" -cp \"%~dp0classes\" Recorder %*\r\nexit /b %errorlevel%\r\n");
            Path output = root.resolve("中文 result file.txt");
            String literal = "中文 value & (x) !literal! %JAVA_HOME% C:\\path\\new\\";
            List<String> command = BuildConfigurationProcess.windowsExecutable(batch);
            command.addAll(List.of(output.toString(), literal));
            BuildConfigurationProcess.run(command, root.toFile(),
                    Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), 30, () -> false);
            assertEquals(literal, Files.readString(output));
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }
}
