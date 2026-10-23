package com.casc.unittestagent.coverage;

import junit.framework.TestCase;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 离线调用真正的 Maven/Gradle，然后用导出配置启动真实测试 JVM。 */
public class BuildRuntimeConfigurationIntegrationTest extends TestCase {
    public void testMavenEffectiveParentProfileAndUserPropertyConfiguration() throws Exception {
        Path root = Files.createTempDirectory("unitmind maven build ");
        Path sandbox = Files.createTempDirectory("unitmind-maven-execution-");
        try {
            Path module = Files.createDirectories(root.resolve("service"));
            Files.createDirectories(module.resolve("run dir"));
            Path source = module.resolve("src/main/java/sample/Subject.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package sample; class Subject {}");
            Files.writeString(root.resolve("pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                    <groupId>sample</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>
                    <modules><module>service</module></modules>
                    <build><pluginManagement><plugins><plugin>
                      <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version>
                      <configuration>
                        <argLine>-Xmx128m &quot;-Dquoted=hello world&quot; -Dpriority=arg</argLine>
                        <systemPropertiesFile>${project.basedir}/test.properties</systemPropertiesFile>
                        <systemPropertyVariables><parentOnly>inherited</parentOnly><priority>variables</priority></systemPropertyVariables>
                        <workingDirectory>${project.basedir}/run dir</workingDirectory>
                      </configuration>
                    </plugin></plugins></pluginManagement></build>
                    <profiles><profile><id>corp</id><build><plugins><plugin>
                      <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                      <configuration><environmentVariables><UNITMIND_BUILD_ENV>maven profile</UNITMIND_BUILD_ENV></environmentVariables></configuration>
                    </plugin></plugins></build></profile></profiles>
                    </project>
                    """);
            Files.writeString(module.resolve("pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                    <parent><groupId>sample</groupId><artifactId>parent</artifactId><version>1</version></parent>
                    <artifactId>service</artifactId>
                    </project>
                    """);
            Files.writeString(module.resolve("test.properties"), "priority=file\nfileOnly=external-file\n");
            Files.createDirectories(root.resolve(".mvn"));
            Files.writeString(root.resolve(".mvn/maven.config"), "-DfromMavenConfig=project config with spaces\n-Dconfig_key=underscore value\n");
            Files.createDirectories(root.resolve(".unitmind"));
            Files.writeString(root.resolve(".unitmind/test-runtime.json"), new JSONObject()
                    .put("arguments", List.of("--offline", "-Pcorp", "-Dpriority=user")).toString());
            IdeMavenBuildConfigurationTest.assertMavenExports(module, module.resolve("pom.xml"),
                    BuildConfigurationProcess.executable(root, "maven", ""), List.of("--offline", "-Pcorp", "-Dpriority=user"));
            TestRuntimeConfiguration runtime = resolve(source, root);
            assertEquals(module.resolve("run dir").toFile(), runtime.getWorkingDirectory());
            assertEquals("inherited", runtime.getSystemProperties().get("parentOnly"));
            assertEquals("project config with spaces", runtime.getSystemProperties().get("fromMavenConfig"));
            TestRuntimeExecutionTest.assertRuntime(sandbox, runtime, """
                    assertEquals("user", System.getProperty("priority"));
                    assertEquals("inherited", System.getProperty("parentOnly"));
                    assertEquals("external-file", System.getProperty("fileOnly"));
                    assertEquals("hello world", System.getProperty("quoted"));
                    assertEquals("project config with spaces", System.getProperty("fromMavenConfig"));
                    assertEquals("underscore value", System.getProperty("config_key"));
                    assertEquals("maven profile", System.getenv("UNITMIND_BUILD_ENV"));
                    assertEquals(%s, java.nio.file.Path.of("").toRealPath().toString());
                    """.formatted(JSONObject.quote(module.resolve("run dir").toRealPath().toString())), false);
        } finally {
            CompiledTestArtifact.deleteRecursively(root);
            CompiledTestArtifact.deleteRecursively(sandbox);
        }
    }

    public void testGradleGroovyMultiProjectConventionAndArgumentProvider() throws Exception {
        gradleFixture(false);
    }

    public void testGradleKotlinMultiProjectConventionAndArgumentProvider() throws Exception {
        gradleFixture(true);
    }

    private void gradleFixture(boolean kotlin) throws Exception {
        String gradle = System.getProperty("unitmind.test.gradleExecutable");
        assertNotNull("Gradle test runtime must expose its own installation", gradle);
        Path root = Files.createTempDirectory("unitmind gradle build ");
        Path sandbox = Files.createTempDirectory("unitmind-gradle-execution-");
        try {
            Files.createDirectories(root.resolve("run dir"));
            Path source = root.resolve("service/src/main/java/sample/Subject.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package sample; class Subject {}");
            Files.createDirectories(root.resolve("other"));
            Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'runtime-fixture'\ninclude('service', 'other')\n");
            if (LocalCoverageClosureSupport.isWindows()) {
                Files.writeString(root.resolve("gradlew.bat"), "@echo off\r\ncall \"" + gradle + "\" %*\r\nexit /b %errorlevel%\r\n");
            } else {
                Files.writeString(root.resolve("gradlew"), "#!/bin/sh\nexec '" + gradle.replace("'", "'\\''") + "' \"$@\"\n");
            }
            Files.writeString(root.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), kotlin ? """
                    import org.gradle.api.tasks.testing.Test
                    import org.gradle.process.CommandLineArgumentProvider
                    subprojects {
                        apply(plugin = "java")
                        tasks.withType<Test>().configureEach {
                            maxHeapSize = "128m"
                            jvmArgs("-Dquoted=hello world")
                            systemProperty("priority", "configured-${project.name}")
                            environment("UNITMIND_BUILD_ENV", "gradle convention")
                            workingDir = rootProject.file("run dir")
                            jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dprovider.key=provider-value") })
                        }
                    }
                    """ : """
                    import org.gradle.process.CommandLineArgumentProvider
                    subprojects {
                        apply plugin: 'java'
                        tasks.withType(Test).configureEach {
                            maxHeapSize = '128m'
                            jvmArgs '-Dquoted=hello world'
                            systemProperty 'priority', "configured-${project.name}"
                            environment 'UNITMIND_BUILD_ENV', 'gradle convention'
                            workingDir rootProject.file('run dir')
                            jvmArgumentProviders.add({ ['-Dprovider.key=provider-value'] } as CommandLineArgumentProvider)
                        }
                    }
                    """);
            Files.createDirectories(root.resolve(".unitmind"));
            Files.writeString(root.resolve(".unitmind/test-runtime.json"), new JSONObject()
                    .put("arguments", List.of("--offline")).put("timeoutSeconds", 120).toString());
            TestRuntimeConfiguration runtime = resolve(source, root);
            assertEquals("Gradle :service:test", runtime.getOrigin());
            assertFalse(runtime.isInheritEnvironment());
            assertTrue(runtime.getJvmArguments().toString(), runtime.getJvmArguments().contains("-Dprovider.key=provider-value"));
            TestRuntimeExecutionTest.assertRuntime(sandbox, runtime, """
                    assertEquals("configured-service", System.getProperty("priority"));
                    assertEquals("hello world", System.getProperty("quoted"));
                    assertEquals("provider-value", System.getProperty("provider.key"));
                    assertEquals("gradle convention", System.getenv("UNITMIND_BUILD_ENV"));
                    assertEquals(%s, java.nio.file.Path.of("").toRealPath().toString());
                    assertTrue(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-Xmx128m"));
                    """.formatted(JSONObject.quote(root.resolve("run dir").toRealPath().toString())), false);
            // 导出任务不能运行 test 的依赖任务，也不能生成编译产物。
            assertFalse(Files.exists(root.resolve("service/build/classes")));
            if (!kotlin) {
                Files.writeString(root.resolve(".unitmind/test-runtime.json"), new JSONObject()
                        .put("arguments", List.of("--offline")).put("gradleTask", ":other:test").toString());
                try { resolve(source, root); fail("another module's runtime must not validate this module's tests"); }
                catch (java.io.IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("another project")); }
                Files.writeString(root.resolve(".unitmind/test-runtime.json"), new JSONObject()
                        .put("arguments", List.of("--offline")).toString());
                // 多个非默认 Test 任务时拒绝猜测；显式指定任务后复用同一配置。
                Files.writeString(root.resolve("build.gradle"), """
                        subprojects {
                            tasks.register('unitOne', Test)
                            tasks.register('unitTwo', Test)
                        }
                        """);
                try { resolve(source, root); fail("ambiguous tasks must not fall back to IDE defaults"); }
                catch (java.io.IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("gradleTask")); }
                Files.writeString(root.resolve(".unitmind/test-runtime.json"), new JSONObject()
                        .put("arguments", List.of("--offline")).put("gradleTask", ":service:unitTwo").toString());
                assertEquals("Gradle :service:unitTwo", resolve(source, root).getOrigin());
                Files.writeString(root.resolve("build.gradle"), """
                        subprojects {
                            tasks.register('unitTwo', Test) { doFirst { systemProperty 'late', 'runtime' } }
                        }
                        """);
                try { resolve(source, root); fail("execution-time configuration cannot be silently ignored"); }
                catch (java.io.IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("execution-time")); }
            }
        } finally {
            CompiledTestArtifact.deleteRecursively(root);
            CompiledTestArtifact.deleteRecursively(sandbox);
        }
    }

    private TestRuntimeConfiguration resolve(Path source, Path root) throws Exception {
        return new ProjectTestRuntimeConfigurationResolver().resolve(source, root,
                Path.of(System.getProperty("java.home"), "bin/java").toString(), root.toFile(), () -> false);
    }
}
