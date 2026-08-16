package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CoverageSnapshot;
import com.casc.unittestagent.model.MethodModel;
import junit.framework.TestCase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 可选的本地 jsoup 真实仓库闭环冒烟测试。
 * 默认跳过；设置 UNITMIND_JSOUP_REPO 和 UNITMIND_JSOUP_CLASSPATH_FILE 后执行。
 */
public class JsoupLocalCoverageClosureTest extends TestCase {
    public void testRealJsoupCoverageClosureAndExistingBaseline() throws Exception {
        Path repo = environmentPath("UNITMIND_JSOUP_REPO");
        Path classpathFile = environmentPath("UNITMIND_JSOUP_CLASSPATH_FILE");
        if (repo == null || !Files.isDirectory(repo.resolve("target/classes"))
                || classpathFile == null || !Files.isRegularFile(classpathFile)) {
            return;
        }

        Path combiningSource = repo.resolve("src/main/java/org/jsoup/select/CombiningEvaluator.java");
        String combiningText = Files.readString(combiningSource, StandardCharsets.UTF_8);
        MethodModel andMatches = method(combiningText,
                "public boolean matches(Element root, Element el)");

        String initialTest = """
                package org.jsoup.select;
                import org.jsoup.nodes.Element;
                import org.junit.jupiter.api.Test;
                import java.util.List;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class UnitMindAndInitialTest {
                    @Test void acceptsWhenEveryEvaluatorMatches() {
                        Element div = new Element("div");
                        CombiningEvaluator.And and = new CombiningEvaluator.And(List.of(new Evaluator.Tag("div")));
                        assertTrue(and.matches(div, div));
                    }
                }
                """;
        String improvedTest = """
                package org.jsoup.select;
                import org.jsoup.nodes.Element;
                import org.junit.jupiter.api.Test;
                import java.util.List;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class UnitMindAndImprovedTest {
                    @Test void acceptsWhenEveryEvaluatorMatches() {
                        Element div = new Element("div");
                        CombiningEvaluator.And and = new CombiningEvaluator.And(List.of(new Evaluator.Tag("div")));
                        assertTrue(and.matches(div, div));
                    }
                    @Test void rejectsWhenAnEvaluatorDoesNotMatch() {
                        Element div = new Element("div");
                        Element span = new Element("span");
                        CombiningEvaluator.And and = new CombiningEvaluator.And(List.of(new Evaluator.Tag("div")));
                        assertFalse(and.matches(div, span));
                    }
                    @Test void acceptsAnEmptyEvaluatorList() {
                        Element div = new Element("div");
                        assertTrue(new CombiningEvaluator.And(List.of()).matches(div, div));
                    }
                }
                """;

        List<File> dependencies = dependencies(classpathFile);
        CoverageRunResult initial = runGeneratedCandidate(repo, dependencies, combiningText, andMatches,
                "org.jsoup.select.UnitMindAndInitialTest", "org.jsoup.select.CombiningEvaluator$And", initialTest);
        CoverageRunResult improved = runGeneratedCandidate(repo, dependencies, combiningText, andMatches,
                "org.jsoup.select.UnitMindAndImprovedTest", "org.jsoup.select.CombiningEvaluator$And", improvedTest);

        assertTrue(details(initial), initial.getSnapshot().isAvailable());
        assertTrue(details(improved), improved.getSnapshot().isAvailable());
        assertTrue("Expected the second real jsoup candidate to increase coverage\ninitial="
                        + metric(initial.getSnapshot()) + "\nimproved=" + metric(improved.getSnapshot()),
                CoverageLoopPolicy.improved(initial.getSnapshot(), improved.getSnapshot()));

        Path evaluatorSource = repo.resolve("src/main/java/org/jsoup/select/Evaluator.java");
        String evaluatorText = Files.readString(evaluatorSource, StandardCharsets.UTF_8);
        MethodModel nodeDispatch = method(evaluatorText, "final boolean matches(Element root, Node node)");
        String dispatchInitialTest = """
                package org.jsoup.select;
                import org.jsoup.nodes.Element;
                import org.jsoup.nodes.Node;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class UnitMindEvaluatorDispatchInitialTest {
                    @Test void dispatchesAnElement() {
                        Element root = new Element("div");
                        Evaluator evaluator = new Evaluator.Tag("div");
                        assertTrue(evaluator.matches(root, (Node) root));
                    }
                }
                """;
        String dispatchImprovedTest = """
                package org.jsoup.select;
                import org.jsoup.nodes.Element;
                import org.jsoup.nodes.LeafNode;
                import org.jsoup.nodes.Node;
                import org.jsoup.nodes.TextNode;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class UnitMindEvaluatorDispatchImprovedTest {
                    private static final class NodeAware extends Evaluator {
                        @Override public boolean matches(Element root, Element element) { return true; }
                        @Override boolean matches(Element root, LeafNode leaf) { return true; }
                        @Override boolean wantsNodes() { return true; }
                    }
                    @Test void dispatchesAnElement() {
                        Element root = new Element("div");
                        assertTrue(new Evaluator.Tag("div").matches(root, (Node) root));
                    }
                    @Test void dispatchesALeafWhenNodesAreWanted() {
                        Element root = new Element("div");
                        assertTrue(new NodeAware().matches(root, (Node) new TextNode("text")));
                    }
                    @Test void rejectsALeafWhenNodesAreNotWanted() {
                        Element root = new Element("div");
                        assertFalse(new Evaluator.Tag("div").matches(root, (Node) new TextNode("text")));
                    }
                }
                """;
        CoverageRunResult dispatchInitial = runGeneratedCandidate(repo, dependencies, evaluatorText, nodeDispatch,
                "org.jsoup.select.UnitMindEvaluatorDispatchInitialTest", "org.jsoup.select.Evaluator", dispatchInitialTest);
        CoverageRunResult dispatchImproved = runGeneratedCandidate(repo, dependencies, evaluatorText, nodeDispatch,
                "org.jsoup.select.UnitMindEvaluatorDispatchImprovedTest", "org.jsoup.select.Evaluator", dispatchImprovedTest);
        assertTrue(details(dispatchInitial), dispatchInitial.getSnapshot().isAvailable());
        assertTrue(details(dispatchImproved), dispatchImproved.getSnapshot().isAvailable());
        assertTrue("Expected Node dispatch supplementation to increase real coverage",
                CoverageLoopPolicy.improved(dispatchInitial.getSnapshot(), dispatchImproved.getSnapshot()));

        CoverageRunResult baseline = runExistingTest(repo, dependencies, evaluatorText, nodeDispatch,
                "org.jsoup.select.EvaluatorTest", "org.jsoup.select.Evaluator");
        assertTrue(details(baseline), baseline.getSnapshot().isAvailable());
        MockitoStaticMockSupport mockitoSupport = MockitoStaticMockSupport.detect(dependencies);

        String report = """
                # UnitMind jsoup 本地闭环冒烟测试

                - jsoup: `%s`
                - 初始候选: %s，缺口 %d
                - 补测候选: %s，缺口 %d
                - 覆盖率有增益: `%s`
                - 是否达到默认阈值: `%s`
                - Node 分派初始候选: %s，缺口 %d
                - Node 分派补测候选: %s，缺口 %d
                - Node 分派覆盖率有增益: `%s`
                - 已有 EvaluatorTest 基线: %s，测试 %d/%d 通过
                - Mockito 静态 Mock: `%s` (%s)
                """.formatted(repo, metric(initial.getSnapshot()), initial.getGaps().size(),
                metric(improved.getSnapshot()), improved.getGaps().size(),
                CoverageLoopPolicy.improved(initial.getSnapshot(), improved.getSnapshot()),
                CoverageLoopPolicy.reached(improved.getSnapshot(), new CoverageConfig()),
                metric(dispatchInitial.getSnapshot()), dispatchInitial.getGaps().size(),
                metric(dispatchImproved.getSnapshot()), dispatchImproved.getGaps().size(),
                CoverageLoopPolicy.improved(dispatchInitial.getSnapshot(), dispatchImproved.getSnapshot()),
                metric(baseline.getSnapshot()), baseline.getSnapshot().getExecution().getSucceeded(),
                baseline.getSnapshot().getExecution().getFound(), mockitoSupport.isSupported(),
                mockitoSupport.getDescription());
        Path output = Path.of("build/reports/unitmind/jsoup-closure-smoke.md").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.println(report);
    }

    private CoverageRunResult runGeneratedCandidate(Path repo,
                                                    List<File> dependencies,
                                                    String sourceText,
                                                    MethodModel method,
                                                    String testClass,
                                                    String targetClass,
                                                    String testCode) throws Exception {
        Path root = Files.createTempDirectory("unitmind-jsoup-candidate-");
        Path classes = root.resolve("classes");
        copyTree(repo.resolve("target/classes"), classes);
        Path sourceFile = root.resolve("source").resolve(testClass.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, testCode, StandardCharsets.UTF_8);
        compile(sourceFile, classes, dependencies);
        return execute(root, classes, dependencies, sourceText, method, testClass, targetClass);
    }

    private CoverageRunResult runExistingTest(Path repo,
                                              List<File> dependencies,
                                              String sourceText,
                                              MethodModel method,
                                              String testClass,
                                              String targetClass) throws Exception {
        Path root = Files.createTempDirectory("unitmind-jsoup-baseline-");
        Path classes = root.resolve("classes");
        copyTree(repo.resolve("target/classes"), classes);
        copyTree(repo.resolve("target/test-classes"), classes);
        return execute(root, classes, dependencies, sourceText, method, testClass, targetClass);
    }

    private CoverageRunResult execute(Path root,
                                      Path classes,
                                      List<File> dependencies,
                                      String sourceText,
                                      MethodModel method,
                                      String testClass,
                                      String targetClass) {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        CoverageConfig config = new CoverageConfig();
        config.setExecutionTimeoutSeconds(60);
        try (CompiledTestArtifact artifact = new CompiledTestArtifact(root, classes,
                testClass, targetClass, java, dependencies, new ArrayList<>())) {
            return new JacocoCoverageExecutor().execute(artifact, method, sourceText, config, () -> false);
        }
    }

    private void compile(Path source, Path classes, List<File> dependencies) throws Exception {
        String javac = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "javac.exe" : "javac").toString();
        List<String> command = new ArrayList<>();
        command.add(javac);
        command.add("--release");
        command.add("17");
        command.add("-g");
        command.add("-cp");
        List<String> classpath = new ArrayList<>();
        classpath.add(classes.toString());
        classpath.add(System.getProperty("java.class.path"));
        for (File dependency : dependencies) classpath.add(dependency.getAbsolutePath());
        command.add(String.join(File.pathSeparator, classpath));
        command.add("-d");
        command.add(classes.toString());
        command.add(source.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.waitFor());
    }

    private MethodModel method(String source, String declaration) {
        String[] lines = source.split("\\R", -1);
        int start = -1;
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].contains(declaration)) {
                start = index + 1;
                break;
            }
        }
        assertTrue("Method declaration not found: " + declaration, start > 0);
        int depth = 0;
        boolean opened = false;
        int end = start;
        for (int index = start - 1; index < lines.length; index++) {
            for (char character : lines[index].toCharArray()) {
                if (character == '{') {
                    depth++;
                    opened = true;
                } else if (character == '}') {
                    depth--;
                }
            }
            end = index + 1;
            if (opened && depth == 0) break;
        }
        MethodModel method = new MethodModel();
        method.setSourceStartLine(start);
        method.setSourceEndLine(end);
        method.setMethodBodyText(String.join("\n", java.util.Arrays.copyOfRange(lines, start - 1, end)));
        return method;
    }

    private List<File> dependencies(Path classpathFile) throws Exception {
        List<File> files = new ArrayList<>();
        String value = Files.readString(classpathFile, StandardCharsets.UTF_8).strip();
        if (value.isBlank()) return files;
        for (String entry : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            File file = new File(entry);
            if (file.exists()) files.add(file);
        }
        return files;
    }

    private void copyTree(Path source, Path target) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Path environmentPath(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private String details(CoverageRunResult result) {
        return result.getSnapshot().getMessage() + "\n" + result.getSnapshot().getExecution().getOutput();
    }

    private String metric(CoverageSnapshot snapshot) {
        String branch = snapshot.getBranch().isApplicable()
                ? percent(snapshot.getBranch().getRate()) : "N/A";
        return "行 " + percent(snapshot.getLine().getRate()) + " (" + snapshot.getLine().getCovered() + "/"
                + snapshot.getLine().getTotal() + ")，分支 " + branch + " ("
                + snapshot.getBranch().getCovered() + "/" + snapshot.getBranch().getTotal() + ")";
    }

    private String percent(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100.0d);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
