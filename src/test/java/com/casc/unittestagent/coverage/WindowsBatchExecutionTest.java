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
            // Record cmd's argument handoff with a native Unicode-aware program. Java launchers'
            // separate ANSI conversion is covered by the real javac/JVM tests.
            Files.writeString(folder.resolve("Recorder.ps1"), "param([string]$OutputPath, [string]$Value)\r\n"
                    + "[System.IO.File]::WriteAllText($OutputPath, $Value, [System.Text.UTF8Encoding]::new($false))\r\n");
            Path batch = folder.resolve("gradlew.bat");
            Files.writeString(batch, "@echo off\r\npowershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"%~dp0Recorder.ps1\" %*\r\nexit /b %errorlevel%\r\n");
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
