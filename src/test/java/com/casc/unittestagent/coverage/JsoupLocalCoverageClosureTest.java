package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.MethodModel;
import junit.framework.TestCase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 可选的本地 jsoup 真实仓库闭环冒烟测试。
 * 默认跳过；设置 UNITMIND_JSOUP_REPO 和 UNITMIND_JSOUP_CLASSPATH_FILE 后执行。
 */
public class JsoupLocalCoverageClosureTest extends TestCase {
    public void testRealJsoupCoverageClosureAndExistingBaseline() throws Exception {
        Path repo = LocalCoverageClosureSupport.environmentPath("UNITMIND_JSOUP_REPO");
        Path classpathFile = LocalCoverageClosureSupport.environmentPath("UNITMIND_JSOUP_CLASSPATH_FILE");
        if (repo == null || !Files.isDirectory(repo.resolve("target/classes"))
                || classpathFile == null || !Files.isRegularFile(classpathFile)) {
            return;
        }

        Path combiningSource = repo.resolve("src/main/java/org/jsoup/select/CombiningEvaluator.java");
        String combiningText = Files.readString(combiningSource, StandardCharsets.UTF_8);
        MethodModel andMatches = LocalCoverageClosureSupport.method(combiningText,
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

        List<File> dependencies = LocalCoverageClosureSupport.dependencies(classpathFile);
        CoverageRunResult initial = runGeneratedCandidate(repo, dependencies, combiningText, andMatches,
                "org.jsoup.select.UnitMindAndInitialTest", "org.jsoup.select.CombiningEvaluator$And", initialTest);
        CoverageRunResult improved = runGeneratedCandidate(repo, dependencies, combiningText, andMatches,
                "org.jsoup.select.UnitMindAndImprovedTest", "org.jsoup.select.CombiningEvaluator$And", improvedTest);

        assertTrue(LocalCoverageClosureSupport.details(initial), initial.getSnapshot().isAvailable());
        assertTrue(LocalCoverageClosureSupport.details(improved), improved.getSnapshot().isAvailable());
        assertTrue("Expected the second real jsoup candidate to increase coverage\ninitial="
                        + LocalCoverageClosureSupport.metric(initial.getSnapshot()) + "\nimproved="
                        + LocalCoverageClosureSupport.metric(improved.getSnapshot()),
                CoverageLoopPolicy.improved(initial.getSnapshot(), improved.getSnapshot()));

        Path evaluatorSource = repo.resolve("src/main/java/org/jsoup/select/Evaluator.java");
        String evaluatorText = Files.readString(evaluatorSource, StandardCharsets.UTF_8);
        MethodModel nodeDispatch = LocalCoverageClosureSupport.method(evaluatorText,
                "final boolean matches(Element root, Node node)");
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
        assertTrue(LocalCoverageClosureSupport.details(dispatchInitial), dispatchInitial.getSnapshot().isAvailable());
        assertTrue(LocalCoverageClosureSupport.details(dispatchImproved), dispatchImproved.getSnapshot().isAvailable());
        assertTrue("Expected Node dispatch supplementation to increase real coverage",
                CoverageLoopPolicy.improved(dispatchInitial.getSnapshot(), dispatchImproved.getSnapshot()));

        CoverageRunResult baseline = runExistingTest(repo, dependencies, evaluatorText, nodeDispatch,
                "org.jsoup.select.EvaluatorTest", "org.jsoup.select.Evaluator");
        assertTrue(LocalCoverageClosureSupport.details(baseline), baseline.getSnapshot().isAvailable());
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
                """.formatted(repo, LocalCoverageClosureSupport.metric(initial.getSnapshot()), initial.getGaps().size(),
                LocalCoverageClosureSupport.metric(improved.getSnapshot()), improved.getGaps().size(),
                CoverageLoopPolicy.improved(initial.getSnapshot(), improved.getSnapshot()),
                CoverageLoopPolicy.reached(improved.getSnapshot(), new CoverageConfig()),
                LocalCoverageClosureSupport.metric(dispatchInitial.getSnapshot()), dispatchInitial.getGaps().size(),
                LocalCoverageClosureSupport.metric(dispatchImproved.getSnapshot()), dispatchImproved.getGaps().size(),
                CoverageLoopPolicy.improved(dispatchInitial.getSnapshot(), dispatchImproved.getSnapshot()),
                LocalCoverageClosureSupport.metric(baseline.getSnapshot()), baseline.getSnapshot().getExecution().getSucceeded(),
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
        LocalCoverageClosureSupport.copyTree(repo.resolve("target/classes"), classes);
        Path sourceFile = root.resolve("source").resolve(testClass.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, testCode, StandardCharsets.UTF_8);
        LocalCoverageClosureSupport.compile(sourceFile, classes, dependencies);
        return LocalCoverageClosureSupport.execute(root, classes, dependencies, sourceText, method, testClass, targetClass);
    }

    private CoverageRunResult runExistingTest(Path repo,
                                              List<File> dependencies,
                                              String sourceText,
                                              MethodModel method,
                                              String testClass,
                                              String targetClass) throws Exception {
        Path root = Files.createTempDirectory("unitmind-jsoup-baseline-");
        Path classes = root.resolve("classes");
        LocalCoverageClosureSupport.copyTree(repo.resolve("target/classes"), classes);
        LocalCoverageClosureSupport.copyTree(repo.resolve("target/test-classes"), classes);
        return LocalCoverageClosureSupport.execute(root, classes, dependencies, sourceText, method, testClass, targetClass);
    }
}
