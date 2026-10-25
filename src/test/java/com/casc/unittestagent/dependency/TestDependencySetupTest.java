package com.casc.unittestagent.dependency;

import com.casc.unittestagent.coverage.*;
import com.casc.unittestagent.llm.LlmConfig;
import com.casc.unittestagent.llm.LlmConfigManager;
import com.casc.unittestagent.model.*;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.casc.unittestagent.service.UnitTestGenerationService;
import com.casc.unittestagent.ui.component.UnitMindHeaderPanel;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.DependencyScope;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TestDependencySetupTest extends LightJavaCodeInsightFixtureTestCase {
    private static final LightProjectDescriptor JDK = new LightProjectDescriptor() {
        @Override public Sdk getSdk() { return JavaSdk.getInstance().createJdk("DependencySetupJdk", System.getProperty("java.home"), false); }
    };
    private static final String SOURCE = """
            package setup;
            public class Subject {
                public interface Gateway { int value(); }
                public int fetch(Gateway gateway) { return gateway.value() + 1; }
            }
            """;
    private static final String POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId><artifactId>setup</artifactId><version>1</version>
              <!-- preserve this project comment -->
              <properties><maven.compiler.source>17</maven.compiler.source><maven.compiler.target>17</maven.compiler.target></properties>
            </project>
            """;
    private Path root;
    private PsiFile source;
    private final TestDependencySetupService service = new TestDependencySetupService();

    @Override protected LightProjectDescriptor getProjectDescriptor() { return JDK; }
    @Override protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setModuleLanguageLevel(getModule(), LanguageLevel.JDK_17, getTestRootDisposable());
        root = Files.createTempDirectory("unitmind-dependency-button-");
        Path file = root.resolve("src/main/java/setup/Subject.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        var content = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/main/java"));
        PsiTestUtil.addSourceRoot(getModule(), content);
        source = PsiManager.getInstance(getProject()).findFile(content.findFileByRelativePath("setup/Subject.java"));
        LlmConfig config = new LlmConfig(); config.setEnabled(false); LlmConfigManager.save(config, null);
    }

    public void testMavenPreviewApplyAndRepeatPreserveBuildAndBackUpOriginal() throws Exception {
        Files.writeString(root.resolve("pom.xml"), POM);
        var context = service.inspect(getProject(), source);
        var plan = service.plan(context, TestDependencySetupService.Mode.PROJECT);
        assertEquals(POM, Files.readString(root.resolve("pom.xml")));
        assertTrue(plan.after(), plan.after().contains("preserve this project comment"));
        var result = service.apply(plan);
        assertEquals(POM, Files.readString(result.backup()));
        assertEquals(plan.after(), Files.readString(root.resolve("pom.xml")));
        assertTrue(Files.isDirectory(root.resolve("src/test/java")));
        var xml = parse(plan.after());
        var dependencies = xml.getElementsByTagNameNS("*", "dependency");
        assertEquals(3, dependencies.getLength());
        for (int i = 0; i < dependencies.getLength(); i++) {
            var dependency = (org.w3c.dom.Element) dependencies.item(i);
            assertEquals("test", dependency.getElementsByTagNameNS("*", "scope").item(0).getTextContent());
        }
        assertTrue(plan.after().contains("maven-surefire-plugin"));
        assertTrue(plan.after().contains("3.5.4"));
        var repeated = service.plan(service.inspect(getProject(), source), TestDependencySetupService.Mode.PROJECT);
        assertEquals(plan.after(), repeated.after());
        assertNull(service.apply(repeated).backup());
    }

    public void testCrLfBuildPreviewAppliesAndPreservesLineSeparators() throws Exception {
        String original = POM.replace("\n", "\r\n");
        Path pom = root.resolve("pom.xml"); Files.writeString(pom, original);
        var plan = service.plan(service.inspect(getProject(), source), TestDependencySetupService.Mode.PROJECT);
        var applied = service.apply(plan);
        assertEquals(original, Files.readString(applied.backup()));
        assertEquals(plan.after(), Files.readString(pom).replace("\r\n", "\n"));
        assertTrue(Files.readString(pom).contains("\r\n"));
    }

    public void testGbkPomPreservesChineseEncodingInBuildAndBackup() throws Exception {
        var gbk = java.nio.charset.Charset.forName("GBK");
        String original = "<?xml version=\"1.0\" encoding=\"GBK\"?>\r\n" + POM.replace("preserve this project comment", "保留原有中文注释").replace("\n", "\r\n");
        Path pom = root.resolve("pom.xml"); Files.writeString(pom, original, gbk);
        var virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom); virtual.setCharset(gbk);
        var plan = service.plan(service.inspect(getProject(), source), TestDependencySetupService.Mode.PROJECT);
        var applied = service.apply(plan);
        assertEquals(original, Files.readString(applied.backup(), gbk));
        assertEquals(plan.after(), Files.readString(pom, gbk).replace("\r\n", "\n"));
        assertTrue(Files.readString(pom, gbk).contains("保留原有中文注释"));
    }

    public void testMavenBomNamespaceAndExistingMockitoVersionArePreserved() throws Exception {
        String before = """
                <m:project xmlns:m="http://maven.apache.org/POM/4.0.0">
                  <m:modelVersion>4.0.0</m:modelVersion>
                  <m:dependencyManagement><m:dependencies><m:dependency>
                    <m:groupId>org.junit</m:groupId><m:artifactId>junit-bom</m:artifactId>
                    <m:version>5.10.2</m:version><m:type>pom</m:type><m:scope>import</m:scope>
                  </m:dependency></m:dependencies></m:dependencyManagement>
                  <m:dependencies><m:dependency>
                    <m:groupId>org.mockito</m:groupId><m:artifactId>mockito-core</m:artifactId>
                    <m:version>${mockito.version}</m:version><m:scope>test</m:scope>
                  </m:dependency></m:dependencies>
                  <m:properties><m:mockito.version>4.11.0</m:mockito.version></m:properties>
                  <m:build><m:plugins><m:plugin><m:artifactId>maven-surefire-plugin</m:artifactId>
                    <m:version>2.12.4</m:version><m:configuration><m:argLine>-Xmx256m</m:argLine></m:configuration>
                  </m:plugin></m:plugins></m:build>
                </m:project>
                """;
        String after = TestBuildFileEditor.maven(getProject(), before, requested(), true);
        var xml = parse(after);
        assertEquals("http://maven.apache.org/POM/4.0.0", xml.getDocumentElement().getNamespaceURI());
        var nodes = xml.getElementsByTagNameNS("*", "dependency");
        int cores = 0;
        for (int i = 0; i < nodes.getLength(); i++) {
            var tag = (org.w3c.dom.Element) nodes.item(i);
            String artifact = tag.getElementsByTagNameNS("*", "artifactId").item(0).getTextContent();
            if (artifact.equals("mockito-core")) cores++;
            if (artifact.equals("junit-jupiter")) assertEquals(0, tag.getElementsByTagNameNS("*", "version").getLength());
            if (artifact.equals("mockito-junit-jupiter")) assertEquals("${mockito.version}", tag.getElementsByTagNameNS("*", "version").item(0).getTextContent());
        }
        assertEquals(1, cores);
        assertTrue(after.contains("3.5.4"));
        assertTrue(after.contains("-Xmx256m"));
    }

    public void testGradleDslVariantsPreserveExistingVersionAndAreIdempotent() {
        for (boolean kotlin : List.of(false, true)) {
            String before = "plugins { id(\"java\") }\n// org.junit.jupiter:junit-jupiter:1.0 is a comment\n"
                    + "dependencies { testImplementation(\"org.mockito:mockito-core:4.11.0\") }\n";
            String after = TestBuildFileEditor.gradle(before, requested(), true, kotlin);
            assertTrue(after.startsWith(before));
            assertTrue(after.contains("mockito-junit-jupiter:4.11.0"));
            assertFalse(after.contains("mockito-core:5.18.0"));
            assertTrue(after.contains(kotlin ? "withType<org.gradle.api.tasks.testing.Test>" : "withType(org.gradle.api.tasks.testing.Test)"));
            assertTrue(after.contains("useJUnitPlatform()"));
            assertEquals(after, TestBuildFileEditor.gradle(after, requested(), true, kotlin));
            String bom = "dependencies { testImplementation(platform(\"org.junit:junit-bom:5.10.2\")) }\n";
            assertTrue(TestBuildFileEditor.gradle(bom, requested(), true, kotlin).contains("\"org.junit.jupiter:junit-jupiter\""));
        }
    }

    public void testOldManagedSurefireDoesNotSilentlySkipJupiterTests() throws Exception {
        String before = POM.replace("</project>", """
                <build><pluginManagement><plugins><plugin>
                <artifactId>maven-surefire-plugin</artifactId><version>2.12.4</version>
                <configuration><argLine>-Xmx256m</argLine></configuration>
                </plugin></plugins></pluginManagement></build></project>
                """);
        String after = TestBuildFileEditor.maven(getProject(), before, requested(), true);
        var plugins = parse(after).getElementsByTagNameNS("*", "plugin");
        assertEquals(2, plugins.getLength());
        assertTrue(after.contains("2.12.4")); // Existing management is retained.
        assertTrue(after.contains("3.5.4")); // Active declaration supports Jupiter.
        assertTrue(after.contains("-Xmx256m"));
    }

    public void testExistingJupiterLibrariesStillRequireCompatibleSurefire() throws Exception {
        Files.writeString(root.resolve("pom.xml"), POM.replace("</project>",
                "<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>"
                        + "<version>2.12.4</version></plugin></plugins></build></project>"));
        var found = service.inspect(getProject(), source);
        var complete = new TestDependencySetupService.Context(found.project(), found.module(), found.directory(),
                found.buildFile(), found.before(), found.diskBeforeHash(), found.refreshRoot(), List.of(), List.of(),
                false, found.note(), found.buildCharset(), found.lineSeparator());
        var plan = service.plan(complete, TestDependencySetupService.Mode.PROJECT);
        assertTrue(plan.after(), plan.after().contains("3.5.4"));
        assertTrue(plan.summary(), plan.summary().contains("不把内置 JAR 安装到 Maven 本地仓库"));
    }

    public void testExistingSurefireWithoutVersionDoesNotInheritUnsupportedDefaults() {
        String before = POM.replace("</project>", "<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>"
                + "<configuration><argLine>-Xmx256m</argLine></configuration></plugin></plugins></build></project>");
        String after = TestBuildFileEditor.maven(getProject(), before, List.of(), true);
        assertTrue(after, after.contains("3.5.4")); assertTrue(after.contains("-Xmx256m"));
        assertEquals(after, TestBuildFileEditor.maven(getProject(), after, List.of(), true));
    }

    public void testSurefireLocalVersionPropertyIsCheckedWithoutChangingProjectProperty() {
        String before = POM.replace("</properties>", "<surefire.version>2.12.4</surefire.version></properties>")
                .replace("</project>", "<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>"
                + "<version>${surefire.version}</version></plugin></plugins></build></project>");
        String after = TestBuildFileEditor.maven(getProject(), before, List.of(), true);
        assertTrue(after, after.contains("3.5.4")); assertTrue(after.contains("<surefire.version>2.12.4</surefire.version>"));
    }

    public void testUnresolvedClasspathProducesActionableImportReceipt() throws Exception {
        Files.writeString(root.resolve("pom.xml"), POM);
        String message = TestDependencySetupService.resolutionMessage(service.inspect(getProject(), source));
        assertTrue(message, message.contains("仍缺少"));
        assertTrue(message, message.contains("org.junit.jupiter:junit-jupiter"));
        assertTrue(message, message.contains("org.mockito:mockito-core"));
        assertTrue(message, message.contains("离线模式"));
    }

    public void testResolvedClasspathReceiptDoesNotClaimTestsHavePassed() throws Exception {
        Files.writeString(root.resolve("pom.xml"), POM);
        var c = service.inspect(getProject(), source);
        var resolved = new TestDependencySetupService.Context(c.project(), c.module(), c.directory(), c.buildFile(),
                c.before(), c.diskBeforeHash(), c.refreshRoot(), List.of(), List.of(), false,
                c.note(), c.buildCharset(), c.lineSeparator());
        String message = TestDependencySetupService.resolutionMessage(resolved);
        assertTrue(message, message.contains("测试可见"));
        assertTrue(message, message.contains("不代表测试已通过"));
    }

    public void testConcurrentDocumentOrDiskChangePreventsApply() throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, POM);
        var plan = service.plan(service.inspect(getProject(), source), TestDependencySetupService.Mode.PROJECT);
        Files.writeString(pom, POM + "<!-- external edit -->");
        try { service.apply(plan); fail(); } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("修改")); }
        assertEquals(POM + "<!-- external edit -->", Files.readString(pom));
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom);
        var document = FileDocumentManager.getInstance().getDocument(file);
        var next = service.plan(service.inspect(getProject(), source), TestDependencySetupService.Mode.PROJECT);
        ApplicationManager.getApplication().runWriteAction(() -> document.setText(document.getText() + "\n<!-- unsaved edit -->"));
        try { service.apply(next); fail(); } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("修改")); }
        assertTrue(document.getText().contains("unsaved edit"));
    }

    public void testOfflineImportUsesTestScopeAndRunsRealMockitoWithoutBuildExport() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "not a buildable pom");
        var plan = service.plan(service.inspect(getProject(), source), TestDependencySetupService.Mode.IDEA_OFFLINE);
        service.apply(plan);
        assertEquals("not a buildable pom", Files.readString(root.resolve("pom.xml")));
        var entries = java.util.Arrays.stream(ModuleRootManager.getInstance(getModule()).getOrderEntries())
                .filter(entry -> entry instanceof LibraryOrderEntry lib && TestDependencySetupService.OFFLINE_LIBRARY.equals(lib.getLibraryName())).toList();
        assertEquals(1, entries.size());
        assertEquals(DependencyScope.TEST, ((LibraryOrderEntry) entries.get(0)).getScope());
        assertEquals(6, ((LibraryOrderEntry) entries.get(0)).getLibrary().getFiles(com.intellij.openapi.roots.OrderRootType.CLASSES).length);
        assertTrue(java.util.Arrays.stream(ModuleRootManager.getInstance(getModule()).getContentEntries())
                .flatMap(entry -> java.util.Arrays.stream(entry.getSourceFolders()))
                .anyMatch(folder -> folder.getUrl().endsWith("/src/test/java") && folder.isTestSource()));
        var environment = new ProjectTestEnvironmentResolver().resolve(getProject(), source);
        assertTrue(environment.getDiagnostics().toString(), environment.isUsable());
        assertFalse(environment.isBundledJunit());
        assertFalse(environment.isBundledMockito());
        assertTrue(environment.hasOfflineTestLibrary());
        String code = """
                package setup;
                class SubjectTest {
                    @org.junit.jupiter.api.Test void stubsCollaborator() {
                        Subject.Gateway gateway = org.mockito.Mockito.mock(Subject.Gateway.class);
                        org.mockito.Mockito.when(gateway.value()).thenReturn(4);
                        org.junit.jupiter.api.Assertions.assertEquals(5, new Subject().fetch(gateway));
                        org.mockito.Mockito.verify(gateway).value();
                        org.junit.jupiter.api.Assertions.assertEquals(1,
                            java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                                .filter(arg -> arg.startsWith("-javaagent:") && arg.contains("mockito-core")).count());
                    }
                }
                """;
        GeneratedTestFile test = new GeneratedTestFile();
        test.setPackageName("setup"); test.setClassName("SubjectTest"); test.setFileName("SubjectTest.java"); test.setContent(code);
        GenerationResult candidate = new GenerationResult();
        candidate.setMethodModel(new JavaSourceAnalyzer().analyze(SOURCE, "fetch")); candidate.setGeneratedTestFile(test);
        GenerationResult result = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(() -> new UnitTestGenerationService().runFinalTestRegression(
                        getProject(), source, candidate, code, () -> false, CoverageProgressListener.NONE), new EmptyProgressIndicator()))
                .get(90, TimeUnit.SECONDS);
        assertEquals(result.getRuntimeValidationReport().getMessage() + result.getRuntimeValidationReport().getFinalExecution().getOutput(),
                ClassJUnitStatus.PASSED, result.getRuntimeValidationReport().getStatus());
        service.apply(service.plan(service.inspect(getProject(), source), TestDependencySetupService.Mode.IDEA_OFFLINE));
        assertEquals(1L, java.util.Arrays.stream(ModuleRootManager.getInstance(getModule()).getOrderEntries())
                .filter(entry -> entry instanceof LibraryOrderEntry lib && TestDependencySetupService.OFFLINE_LIBRARY.equals(lib.getLibraryName())).count());
    }

    public void testOfflineLibraryCanBeMigratedToPersistentProjectDependencies() throws Exception {
        Files.writeString(root.resolve("pom.xml"), POM);
        service.apply(service.plan(service.inspect(getProject(), source), TestDependencySetupService.Mode.IDEA_OFFLINE));
        var context = service.inspect(getProject(), source);
        assertEquals(3, context.dependencies().size());
        var plan = service.plan(context, TestDependencySetupService.Mode.PROJECT);
        service.apply(plan);
        assertTrue(Files.readString(root.resolve("pom.xml")).contains("junit-jupiter"));
        assertTrue(Files.readString(root.resolve("pom.xml")).contains("mockito-core"));
        assertFalse(java.util.Arrays.stream(ModuleRootManager.getInstance(getModule()).getOrderEntries())
                .anyMatch(entry -> entry instanceof LibraryOrderEntry lib && TestDependencySetupService.OFFLINE_LIBRARY.equals(lib.getLibraryName())));
    }

    public void testHeaderButtonIsAvailableBeforeAnalysisAndRespectsBusyState() {
        var clicks = new java.util.concurrent.atomic.AtomicInteger();
        UnitMindHeaderPanel header = new UnitMindHeaderPanel(() -> {}, () -> {}, clicks::incrementAndGet);
        javax.swing.JButton button = button(header);
        assertNotNull(button);
        button.doClick(); assertEquals(1, clicks.get());
        header.setDependenciesEnabled(false); button.doClick(); assertEquals(1, clicks.get());
    }

    private static javax.swing.JButton button(Container parent) {
        for (var component : parent.getComponents()) {
            if (component instanceof javax.swing.JButton button && button.getText().equals("配置测试依赖")) return button;
            if (component instanceof Container container) { var found = button(container); if (found != null) return found; }
        }
        return null;
    }
    private static List<TestBuildFileEditor.Dependency> requested() {
        return List.of(new TestBuildFileEditor.Dependency("org.junit.jupiter", "junit-jupiter", "5.12.0"),
                new TestBuildFileEditor.Dependency("org.mockito", "mockito-core", "5.18.0"),
                new TestBuildFileEditor.Dependency("org.mockito", "mockito-junit-jupiter", "5.18.0"));
    }
    private static org.w3c.dom.Document parse(String xml) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Override protected void tearDown() throws Exception {
        try {
            ApplicationManager.getApplication().runWriteAction(() -> com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(getModule(), model -> {
                var library = model.getModuleLibraryTable().getLibraryByName(TestDependencySetupService.OFFLINE_LIBRARY);
                if (library != null) model.getModuleLibraryTable().removeLibrary(library);
                if (root != null) for (var entry : model.getContentEntries()) {
                    if (entry.getUrl().contains(root.getFileName().toString())) model.removeContentEntry(entry);
                }
            }));
            super.tearDown();
        }
        finally { if (root != null) try (var files = Files.walk(root)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        } }
    }
}
