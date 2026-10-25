package com.casc.unittestagent.dependency;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Builds generic fixtures with the exact generated edits; no real API or private project inputs. */
public class TestBuildFileExecutionTest extends LightJavaCodeInsightFixtureTestCase {
    private Path buildDirectory;
    private static final List<TestBuildFileEditor.Dependency> DEPENDENCIES = List.of(
            new TestBuildFileEditor.Dependency("org.junit.jupiter", "junit-jupiter", "5.12.0"),
            new TestBuildFileEditor.Dependency("org.mockito", "mockito-core", "5.18.0"),
            new TestBuildFileEditor.Dependency("org.mockito", "mockito-junit-jupiter", "5.18.0"));

    public void testGeneratedMavenConfigurationExecutesJUnitWithMockito() throws Exception { maven(false); }

    public void testExistingLibrariesWithOldSurefireExecuteTestsAfterConfiguration() throws Exception { maven(true); }

    public void testMixedJUnit4AndJupiterConfigurationRunsBothTestClasses() throws Exception { maven(true, true); }

    private void maven(boolean existingDependencies) throws Exception { maven(existingDependencies, false); }

    private void maven(boolean existingDependencies, boolean legacyJUnit) throws Exception {
        Path root = fixture();
        try {
            String before = """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>dependency-button</artifactId><version>1</version>
                      <properties><maven.compiler.source>17</maven.compiler.source><maven.compiler.target>17</maven.compiler.target>
                      <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                      <build><directory>${env.UNITMIND_FIXTURE_BUILD}</directory></build>
                    </project>
                    """;
            if (existingDependencies) {
                StringBuilder declarations = new StringBuilder("<dependencies>");
                for (var dependency : DEPENDENCIES) declarations.append("<dependency><groupId>")
                        .append(dependency.group()).append("</groupId><artifactId>").append(dependency.artifact())
                        .append("</artifactId><version>").append(dependency.version())
                        .append("</version><scope>test</scope></dependency>");
                declarations.append("</dependencies>");
                before = before.replace("</build>", "<plugins><plugin><artifactId>maven-surefire-plugin</artifactId>"
                        + "<version>2.12.4</version></plugin></plugins></build>")
                        .replace("</project>", declarations + "</project>");
            }
            if (legacyJUnit) {
                before = before.replace("</dependencies>", "<dependency><groupId>junit</groupId><artifactId>junit</artifactId>"
                        + "<version>4.13.2</version><scope>test</scope></dependency></dependencies>");
                Path legacy = root.resolve("src/test/java/example/LegacySubjectTest.java");
                Files.writeString(legacy, "package example; public class LegacySubjectTest {"
                        + "@org.junit.Test public void legacyContract() { org.junit.Assert.assertTrue(true); } }");
            }
            Files.writeString(root.resolve("pom.xml"), TestBuildFileEditor.maven(getProject(), before,
                    existingDependencies ? List.of() : DEPENDENCIES, true));
            List<String> command = new ArrayList<>(List.of(isWindows() ? "mvn.cmd" : "mvn", "-B", "-q", "-ntp"));
            if (!online()) command.add("--offline");
            command.add("test");
            run(root, command);
            assertReport(buildDirectory.resolve("surefire-reports/TEST-example.SubjectTest.xml"));
            if (legacyJUnit) assertReport(buildDirectory.resolve("surefire-reports/TEST-example.LegacySubjectTest.xml"));
        } finally { delete(root); delete(buildDirectory); }
    }

    public void testGeneratedGradleGroovyConfigurationExecutesJUnitWithMockito() throws Exception { gradle(false); }
    public void testGeneratedGradleKotlinConfigurationExecutesJUnitWithMockito() throws Exception { gradle(true); }

    private void gradle(boolean kotlin) throws Exception {
        Path root = fixture();
        try {
            Files.writeString(root.resolve("settings.gradle"), "rootProject.name='unitmind-dependency-button'\n");
            String before = "plugins { id(\"java\") }\nrepositories { mavenCentral() }\n"
                    + "layout.buildDirectory.set(file(System.getenv(\"UNITMIND_FIXTURE_BUILD\")))\n";
            Files.writeString(root.resolve(kotlin ? "build.gradle.kts" : "build.gradle"),
                    TestBuildFileEditor.gradle(before, DEPENDENCIES, true, kotlin));
            List<String> command = new ArrayList<>(List.of(System.getProperty("unitmind.test.gradleExecutable"),
                    "--no-daemon", "--console=plain", "-q"));
            if (!online()) command.add("--offline");
            command.add("test");
            run(root, command);
            assertReport(buildDirectory.resolve("test-results/test/TEST-example.SubjectTest.xml"));
        } finally { delete(root); delete(buildDirectory); }
    }

    private Path fixture() throws Exception {
        Path root = Files.createTempDirectory("unitmind dependency build 中文 ");
        // Gradle 8's native-encoding worker argfiles cannot represent this checkout on Cp1252.
        // Keep the real Unicode project and its sources; use an explicit ASCII output directory.
        buildDirectory = Files.createTempDirectory("unitmind-dependency-output-");
        Path source = root.resolve("src/main/java/example/Subject.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;
                public class Subject {
                    public interface Gateway { int value(); }
                    public int fetch(Gateway gateway) { return gateway.value() + 1; }
                }
                """);
        Path test = root.resolve("src/test/java/example/SubjectTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, """
                package example;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import static org.mockito.Mockito.*;
                import static org.junit.jupiter.api.Assertions.*;
                @ExtendWith(MockitoExtension.class)
                class SubjectTest {
                    @Mock Subject.Gateway gateway;
                    @Test void stubsBusinessDependency() {
                        when(gateway.value()).thenReturn(4);
                        assertEquals(5, new Subject().fetch(gateway));
                        verify(gateway).value();
                    }
                }
                """);
        return root;
    }

    private void run(Path root, List<String> command) throws Exception {
        Path output = root.resolve("build-output.txt");
        if (isWindows() && command.get(0).toLowerCase(java.util.Locale.ROOT).matches(".*\\.(cmd|bat)$")) {
            command = new ArrayList<>(command);
            if (!Path.of(command.get(0)).isAbsolute()) {
                Path binary = root.resolve(command.get(0));
                if (!Files.isRegularFile(binary)) {
                    for (String entry : System.getenv("PATH").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                        if (entry.isBlank()) continue;
                        Path candidate = Path.of(entry.replace("\"", "")).resolve(command.get(0));
                        if (Files.isRegularFile(candidate)) { binary = candidate; break; }
                    }
                }
                assertTrue("Native build tool was not found", Files.isRegularFile(binary));
                command.set(0, binary.toString());
            }
            command.addAll(0, List.of("cmd.exe", "/d", "/v:off", "/c"));
        }
        var builder = new com.casc.unittestagent.util.BuildToolCommandLine(command)
                .withWorkDirectory(root.toFile()).withRedirectErrorStream(true)
                .withEnvironment("JAVA_HOME", System.getProperty("java.home"))
                .withEnvironment("UNITMIND_FIXTURE_BUILD", buildDirectory.toString())
                .withEnvironment("MAVEN_BASEDIR", ".");
        Process process = builder.createProcess();
        var drain = new Thread(() -> {
            try (var input = process.getInputStream(); var target = Files.newOutputStream(output)) {
                input.transferTo(target);
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        }, "unitmind-build-fixture-output");
        drain.setDaemon(true);
        drain.start();
        process.getOutputStream().close();
        try {
            assertTrue("Build exceeded 180 seconds", process.waitFor(180, TimeUnit.SECONDS));
            drain.join(5000);
            assertFalse("Build output drain did not finish", drain.isAlive());
            assertEquals(Files.readString(output) + failedBuildReports(root), 0, process.exitValue());
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    /** Keep the synthetic child JVM's real failure visible before the fixture is removed. */
    private String failedBuildReports(Path root) throws Exception {
        StringBuilder result = new StringBuilder();
        for (String relative : List.of("test-results/test", "surefire-reports")) {
            Path reports = buildDirectory.resolve(relative);
            if (!Files.isDirectory(reports)) continue;
            try (var files = Files.list(reports)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".xml")
                        || p.getFileName().toString().endsWith(".dumpstream")).sorted().toList()) {
                    String text = Files.readString(file);
                    result.append('\n').append(file.getFileName()).append('\n')
                            .append(text, 0, Math.min(text.length(), 20000));
                }
            }
        }
        return result.toString();
    }

    private void assertReport(Path file) throws Exception {
        assertTrue("No real JUnit XML report: " + file, Files.isRegularFile(file));
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var suite = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        assertEquals("1", suite.getAttribute("tests"));
        assertEquals("0", suite.getAttribute("failures"));
        assertEquals("0", suite.getAttribute("errors"));
    }
    private static boolean online() { return Boolean.getBoolean("unitmind.test.setupFixturesOnline"); }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
    private static void delete(Path root) throws Exception {
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
