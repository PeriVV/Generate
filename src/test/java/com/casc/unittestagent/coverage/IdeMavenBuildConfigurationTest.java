package com.casc.unittestagent.coverage;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.idea.maven.project.MavenGeneralSettings;
import org.jetbrains.idea.maven.project.MavenWorkspaceSettingsComponent;
import org.jetbrains.idea.maven.server.MavenDistributionsCache;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class IdeMavenBuildConfigurationTest extends LightJavaCodeInsightFixtureTestCase {
    private MavenGeneralSettings previous;
    private Path root;
    private Path mavenHome;

    @Override protected void setUp() throws Exception {
        super.setUp();
        var workspace = MavenWorkspaceSettingsComponent.getInstance(getProject()).getSettings();
        previous = workspace.getGeneralSettings();
        workspace.setGeneralSettings(new MavenGeneralSettings(getProject()));
        mavenHome = MavenDistributionsCache.resolveEmbeddedMavenHome().getMavenHome();
        settings().setMavenHome(mavenHome.toString());
        MavenDistributionsCache.getInstance(getProject()).cleanCaches();
        root = Files.createTempDirectory("unitmind-idea-maven-");
    }

    public void testIdeaMavenRunsExportWhenProjectHasOnlyWindowsWrapper() throws Exception {
        Files.writeString(root.resolve("mvnw.cmd"), "@echo This Windows wrapper must not run on macOS\n");
        var ide = IdeMavenBuildConfiguration.resolve(getProject(), root, true);
        assertEquals(mavenHome.resolve(BuildConfigurationProcess.isWindows() ? "bin/mvn.cmd" : "bin/mvn").toString(), ide.executable());
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId>
                <artifactId>only-windows-wrapper</artifactId><version>1</version>
                <build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><configuration>
                  <argLine>-Xmx128m</argLine>
                  <systemPropertyVariables><fromIdeaExport>yes</fromIdeaExport></systemPropertyVariables>
                </configuration></plugin></plugins></build></project>
                """);
        Path source = root.resolve("src/main/java/Subject.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Subject {}");
        assertMavenExports(root, root.resolve("pom.xml"),
                BuildConfigurationProcess.executable(root, "maven", ide.executable()), List.of("--offline"));
        settings().setWorkOffline(true);
        TestRuntimeConfiguration runtime = background(() -> new ProjectTestRuntimeConfigurationResolver().resolve(
                source, root, java(), root.toFile(), () -> false, false, getProject()));
        assertEquals("yes", runtime.getSystemProperties().get("fromIdeaExport"));
        assertTrue(runtime.getOrigin(), runtime.getOrigin().contains("IDEA Maven 设置"));
        Path sandbox = Files.createTempDirectory(root, "junit-");
        TestRuntimeExecutionTest.assertRuntime(sandbox, runtime,
                "assertEquals(\"yes\", System.getProperty(\"fromIdeaExport\"));", false);
    }

    static void assertMavenExports(Path directory, Path pom, List<String> executable, List<String> arguments) throws Exception {
        for (String goal : List.of("effective-pom", "evaluate")) {
            assertMavenExport(directory, pom, executable, arguments, goal);
        }
    }

    private static void assertMavenExport(Path directory, Path pom, List<String> executable,
                                          List<String> arguments, String goal) throws Exception {
        List<String> command = new java.util.ArrayList<>(executable);
        Path output = directory.resolve("maven-" + goal + "-output.txt");
        command.addAll(arguments);
        command.addAll(List.of("-B", "-N", "-f", pom.toString(),
                "org.apache.maven.plugins:maven-help-plugin:3.5.2:" + goal,
                "-Doutput=" + directory.resolve(goal + ".xml")));
        if (goal.equals("evaluate")) command.add("-Dexpression=session.userProperties");
        Process process = new com.casc.unittestagent.util.BuildToolCommandLine(command)
                .withWorkDirectory(directory.toFile()).withRedirectErrorStream(true)
                .withParentEnvironmentType(com.intellij.execution.configurations.GeneralCommandLine.ParentEnvironmentType.SYSTEM)
                .createProcess();
        Thread drain = new Thread(() -> {
            try (var input = process.getInputStream(); var target = Files.newOutputStream(output)) {
                input.transferTo(target);
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        }, "unitmind-synthetic-maven-output");
        drain.setDaemon(true);
        drain.start();
        process.getOutputStream().close();
        try {
            assertTrue("Synthetic Maven export timed out", process.waitFor(90, TimeUnit.SECONDS));
            drain.join(5000);
            assertFalse("Maven output drain did not finish", drain.isAlive());
            assertEquals(Files.readString(output), 0, process.exitValue());
        } finally { ProcessCleanup.terminate(process); }
    }

    public void testIdeaRepositorySettingsAreReadWithoutChangingProjectFiles() throws Exception {
        Path userSettings = root.resolve("corporate settings.xml");
        Files.writeString(userSettings, "<settings/>");
        settings().setUserSettingsFile(userSettings.toString());
        settings().setLocalRepository(root.resolve("repository with spaces").toString());
        settings().setWorkOffline(true);
        var config = IdeMavenBuildConfiguration.resolve(getProject(), root, true);
        assertEquals(List.of("--settings", userSettings.toString(),
                "-Dmaven.repo.local=" + root.resolve("repository with spaces"), "--offline"), config.arguments());
        assertEquals("<settings/>", Files.readString(userSettings));
        assertFalse(Files.exists(root.resolve(".unitmind/test-runtime.json")));
    }

    public void testExplicitExecutableDoesNotResolveBrokenIdeaInstallation() throws Exception {
        settings().setMavenHome(root.resolve("missing-maven").toString());
        MavenDistributionsCache.getInstance(getProject()).cleanCaches();
        var config = IdeMavenBuildConfiguration.resolve(getProject(), root, false);
        assertEquals("", config.executable());
        assertNotNull(config.arguments());
    }

    public void testExplicitSettingsAndRepositoryArgumentsOverrideIdeaDefaults() {
        var ide = List.of("--settings", "idea.xml", "-Dmaven.repo.local=idea-repo", "--offline");
        assertEquals(ide, ProjectTestRuntimeConfigurationResolver.mavenIdeArguments(ide, List.of()));
        assertEquals(List.of(), ProjectTestRuntimeConfigurationResolver.mavenIdeArguments(ide,
                List.of("--settings=local.xml", "-Dmaven.repo.local=local", "--offline")));
        assertEquals(List.of("-Dmaven.repo.local=idea-repo", "--offline"),
                ProjectTestRuntimeConfigurationResolver.mavenIdeArguments(ide, List.of("-slocal.xml")));
    }

    private MavenGeneralSettings settings() {
        return MavenWorkspaceSettingsComponent.getInstance(getProject()).getSettings().getGeneralSettings();
    }
    private String java() {
        return Path.of(System.getProperty("java.home"), "bin", BuildConfigurationProcess.isWindows() ? "java.exe" : "java").toString();
    }
    private <T> T background(java.util.concurrent.Callable<T> action) throws Exception {
        return ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(() -> {
                    try { return action.call(); } catch (Exception error) { throw new RuntimeException(error); }
                }, new EmptyProgressIndicator())).get(120, TimeUnit.SECONDS);
    }
    @Override protected void tearDown() throws Exception {
        try {
            MavenWorkspaceSettingsComponent.getInstance(getProject()).getSettings().setGeneralSettings(previous);
            MavenDistributionsCache.getInstance(getProject()).cleanCaches();
            super.tearDown();
        } finally { if (root != null) CompiledTestArtifact.deleteRecursively(root); }
    }
}
