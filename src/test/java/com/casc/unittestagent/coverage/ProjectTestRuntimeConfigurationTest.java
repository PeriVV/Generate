package com.casc.unittestagent.coverage;

import com.intellij.openapi.progress.ProcessCanceledException;
import junit.framework.TestCase;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ProjectTestRuntimeConfigurationTest extends TestCase {
    public void testWindowsEnvironmentExclusionsAndOverridesIgnoreKeyCase() {
        var environment = new java.util.HashMap<>(Map.of("Path", "old path", "JAVA_HOME", "old jdk", "Keep", "yes"));
        var runtime = new TestRuntimeConfiguration(List.of(), Map.of(), Map.of("java_home", "chosen jdk"),
                Set.of("PATH"), true, null, "", "test");
        JacocoCoverageExecutor.configureEnvironment(environment, runtime, true);
        assertFalse(environment.containsKey("Path")); assertFalse(environment.containsKey("JAVA_HOME"));
        assertEquals("chosen jdk", environment.get("java_home")); assertEquals("yes", environment.get("Keep"));
        environment.put("Path", "unix case sensitive");
        JacocoCoverageExecutor.configureEnvironment(environment, runtime, false);
        assertEquals("unix case sensitive", environment.get("Path"));
    }
    public void testMissingJunitUsesOfflineSandboxWithoutInvokingEitherBuildTool() throws Exception {
        Path root = Files.createTempDirectory("unitmind-offline-default-");
        try {
            Path source = root.resolve("src/main/java/Subject.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "class Subject {}");
            // Invalid descriptions deliberately prove that automatic sandbox selection does not evaluate builds.
            Files.writeString(root.resolve("pom.xml"), "not an effective POM");
            Files.writeString(root.resolve("build.gradle"), "throw new RuntimeException('must not run')");
            var resolver = new ProjectTestRuntimeConfigurationResolver();
            var config = resolver.resolve(source, root, "", root.toFile(), () -> false, true);
            assertTrue(config.getOrigin(), config.getOrigin().contains("内置离线沙箱"));
            assertTrue(config.getJvmArguments().isEmpty());
            Files.createDirectories(root.resolve(".unitmind"));
            Path options = root.resolve(".unitmind/test-runtime.json");
            Files.writeString(options, "{\"executionMode\":\"project\"}");
            try { resolver.resolve(source, root, "", root.toFile(), () -> false, true); fail(); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("同时包含")); }
            Files.writeString(options, "{\"buildTool\":\"invalid\"}");
            try { resolver.resolve(source, root, "", root.toFile(), () -> false, true); fail(); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("buildTool")); }
            Files.writeString(options, "{\"executionMode\":\"sandbox\",\"buildTool\":\"maven\"}");
            try { resolver.resolve(source, root, "", root.toFile(), () -> false, true); fail(); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("不能同时")); }
            Files.writeString(options, "{\"runtime\":{\"systemProperties\":{\"local.flag\":\"yes\"}}}");
            config = resolver.resolve(source, root, "", root.toFile(), () -> false, true);
            assertEquals("yes", config.getSystemProperties().get("local.flag"));
            assertTrue(config.getOrigin().contains("插件内置 JUnit"));
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }

    public void testMavenPropertyPrecedenceExecutionOverlayAndQuotedArguments() throws Exception {
        Path root = Files.createTempDirectory("unitmind-maven-config-");
        try {
            Files.createDirectories(root.resolve("run dir"));
            Files.writeString(root.resolve("test.properties"), "priority=file\nfileOnly=value from file\n");
            Path pom = root.resolve("effective.xml");
            Files.writeString(pom, """
                    <project><properties><memory>128m</memory><late>-Dlate=value</late></properties>
                    <build><directory>target</directory><plugins><plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <configuration>
                      <argLine>-Xmx${memory} -Dpriority=arg &quot;-Dwith.space=hello world&quot; --add-opens java.base/java.lang=ALL-UNNAMED ${late}</argLine>
                      <systemProperties><property><name>priority</name><value>legacy</value></property></systemProperties>
                      <systemPropertiesFile>${basedir}/test.properties</systemPropertiesFile>
                      <systemPropertyVariables><priority>variables</priority><parentOnly>parent</parentOnly></systemPropertyVariables>
                      <environmentVariables><CONFIG_MODE>test profile</CONFIG_MODE><LITERAL_AT>@{literal}</LITERAL_AT></environmentVariables>
                      <excludedEnvironmentVariables><name>SECRET_ONE</name><name>SECRET_TWO</name></excludedEnvironmentVariables>
                      <workingDirectory>${project.basedir}/run dir</workingDirectory>
                    </configuration>
                    <executions><execution><id>default-test</id><goals><goal>test</goal></goals>
                      <configuration><systemPropertyVariables><childOnly>child</childOnly></systemPropertyVariables></configuration>
                    </execution></executions>
                    </plugin></plugins></build></project>
                    """);
            TestRuntimeConfiguration config = MavenTestRuntimeConfiguration.parse(pom, root, "", Map.of("priority", "user"));
            assertEquals("user", config.getSystemProperties().get("priority"));
            assertEquals("parent", config.getSystemProperties().get("parentOnly"));
            assertEquals("child", config.getSystemProperties().get("childOnly"));
            assertEquals("value from file", config.getSystemProperties().get("fileOnly"));
            assertTrue(config.getJvmArguments().toString(), config.getJvmArguments().contains("-Dwith.space=hello world"));
            assertTrue(config.getJvmArguments().contains("-Xmx128m"));
            assertTrue(config.getJvmArguments().contains("-Dlate=value"));
            assertTrue(config.getJvmArguments().contains("--add-opens"));
            assertEquals("test profile", config.getEnvironment().get("CONFIG_MODE"));
            assertEquals("@{literal}", config.getEnvironment().get("LITERAL_AT"));
            assertEquals(Set.of("SECRET_ONE", "SECRET_TWO"), config.getExcludedEnvironment());
            assertEquals(root.resolve("run dir").toFile(), config.getWorkingDirectory());
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }

    public void testMavenLongAndShortUserPropertySyntax() {
        assertEquals(Map.of("first", "one", "second", "two", "third", "three", "flag", "true"),
                MavenTestRuntimeConfiguration.userProperties(List.of("-Dfirst=one", "--define=second=two", "--define", "third=three", "-Dflag")));
    }

    public void testMavenAmbiguousExecutionsAndUnresolvedLatePropertiesFailExplicitly() throws Exception {
        Path root = Files.createTempDirectory("unitmind-maven-ambiguity-");
        try {
            Path pom = root.resolve("effective.xml");
            Files.writeString(pom, """
                    <project><build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>
                    <executions>
                      <execution><id>one</id><goals><goal>test</goal></goals><configuration><argLine>-Done=1</argLine></configuration></execution>
                      <execution><id>two</id><goals><goal>test</goal></goals><configuration><argLine>@{secretValue}</argLine></configuration></execution>
                    </executions></plugin></plugins></build></project>
                    """);
            try { MavenTestRuntimeConfiguration.parse(pom, root, "", Map.of()); fail(); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("多个")); }
            assertTrue(MavenTestRuntimeConfiguration.parse(pom, root, "one", Map.of()).getJvmArguments().contains("-Done=1"));
            try { MavenTestRuntimeConfiguration.parse(pom, root, "two", Map.of()); fail(); }
            catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("未求值"));
                assertFalse(expected.getMessage().contains("secretValue"));
            }
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }

    public void testMavenUserPropertiesMayBeDisabledAndXmlExternalEntitiesRejected() throws Exception {
        Path root = Files.createTempDirectory("unitmind-maven-xml-");
        try {
            Path pom = root.resolve("effective.xml");
            Files.writeString(pom, """
                    <project><build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><configuration>
                      <promoteUserPropertiesToSystemProperties>false</promoteUserPropertiesToSystemProperties>
                      <systemPropertyVariables><priority>configured</priority></systemPropertyVariables>
                    </configuration></plugin></plugins></build></project>
                    """);
            assertEquals("configured", MavenTestRuntimeConfiguration.parse(pom, root, "", Map.of("priority", "user"))
                    .getSystemProperties().get("priority"));
            Files.writeString(pom, "<!DOCTYPE project [<!ENTITY leak SYSTEM 'file:///etc/passwd'>]><project>&leak;</project>");
            try { MavenTestRuntimeConfiguration.parse(pom, root, "", Map.of()); fail(); }
            catch (org.xml.sax.SAXParseException expected) { }
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }

    public void testNoBuildProjectAndExplicitLocalRuntimeNeedNoBuildProcess() throws Exception {
        Path root = Files.createTempDirectory("unitmind-runtime-local-");
        try {
            Path source = root.resolve("src/Subject.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "class Subject {}");
            ProjectTestRuntimeConfigurationResolver resolver = new ProjectTestRuntimeConfigurationResolver();
            assertEquals("IDE", resolver.resolve(source, root, "", root.toFile(), () -> false).getOrigin());
            Files.createDirectories(root.resolve(".unitmind"));
            Files.writeString(root.resolve(".unitmind/test-runtime.json"), new JSONObject().put("runtime", new JSONObject()
                    .put("jvmArgs", List.of("-Xmx128m")).put("systemProperties", Map.of("key", "literal $(not-a-command)"))
                    .put("workingDirectory", ".")).toString());
            TestRuntimeConfiguration config = resolver.resolve(source, root, "", root.toFile(), () -> false);
            assertEquals("literal $(not-a-command)", config.getSystemProperties().get("key"));
            try { resolver.resolve(source, root, "", root.toFile(), () -> true); fail(); }
            catch (ProcessCanceledException expected) { }
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }

    public void testBuildExportFailureTimeoutAndCancellationDoNotExposeOutput() throws Exception {
        Path root = Files.createTempDirectory("unitmind-export-process-");
        try {
            boolean windows = LocalCoverageClosureSupport.isWindows();
            List<String> failure = windows
                    ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Write-Output 'SENSITIVE_TEST_VALUE'; exit 3")
                    : List.of("/bin/sh", "-c", "printf 'SENSITIVE_TEST_VALUE'; exit 3");
            List<String> sleeper = windows
                    ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 30")
                    : List.of("/bin/sh", "-c", "sleep 30");
            try {
                BuildConfigurationProcess.run(failure, root.toFile(), "", 5, () -> false);
                fail();
            } catch (java.io.IOException expected) {
                assertFalse(expected.getMessage().contains("SENSITIVE_TEST_VALUE"));
                assertTrue(expected.getMessage().contains("3"));
            }
            try {
                BuildConfigurationProcess.run(sleeper, root.toFile(), "", 1, () -> false);
                fail();
            } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("超时")); }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200);
            try {
                BuildConfigurationProcess.run(sleeper, root.toFile(), "", 5, () -> System.nanoTime() > deadline);
                fail();
            } catch (ProcessCanceledException expected) { }
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }

    public void testInvalidDirectoryAndLaunchOverridesAreRejected() throws Exception {
        Path root = Files.createTempDirectory("unitmind-runtime-invalid-");
        try {
            for (List<String> args : List.of(List.of("-cp", "other"), List.of("@args.txt"), List.of("-jar"), List.of("--add-opens"))) {
                try { new TestRuntimeConfiguration(args, Map.of(), Map.of(), Set.of(), true, root.toFile(), "", "test"); fail(args.toString()); }
                catch (IllegalArgumentException expected) { }
            }
            try { TestRuntimeConfiguration.fromJson(new JSONObject().put("workingDirectory", "missing"), root.toFile(), "test"); fail(); }
            catch (IllegalArgumentException expected) { }
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }
}
