package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.casc.unittestagent.model.RuntimeFailurePhase;
import junit.framework.TestCase;
import java.util.List;

public class FixtureLifecycleAnalyzerTest extends TestCase {
    private static final String SOURCE = "package sample; class Subject { Subject(Repository repository) {} }";
    private static final String TEST = """
            package sample;
            import org.mockito.Spy;
            import org.junit.jupiter.api.extension.ExtendWith;
            import org.mockito.junit.jupiter.MockitoExtension;
            @ExtendWith(MockitoExtension.class)
            class SubjectTest {
                @Spy Subject service;
                @org.junit.jupiter.api.BeforeEach void setup() { service = new Subject(repository); }
                @org.junit.jupiter.api.Test void fails() {}
            }
            """;

    public void testPreflightDetectsInitializationBeforeBeforeEachWithoutRunningCode() {
        var diagnostics = FixtureLifecycleAnalyzer.preflight(SOURCE, TEST);
        assertEquals(1, diagnostics.size());
        assertEquals("FIXTURE_INITIALIZATION_CONFLICT", diagnostics.get(0).getCode());
        assertTrue(diagnostics.get(0).getMessage().contains("Mockito.spy"));
        assertTrue(diagnostics.get(0).getMessage().contains("保持测试正文"));
    }

    public void testPreflightRespectsNoArgConstructorsInitializedFieldsAndInjectMocks() {
        assertTrue(FixtureLifecycleAnalyzer.preflight(SOURCE.replace("Subject(Repository repository)", "Subject()"), TEST).isEmpty());
        assertTrue(FixtureLifecycleAnalyzer.preflight(SOURCE, TEST.replace("Subject service;", "Subject service = new Subject(repository);")).isEmpty());
        assertTrue(FixtureLifecycleAnalyzer.preflight(SOURCE, TEST.replace("@Spy Subject", "@org.mockito.InjectMocks @Spy Subject")).isEmpty());
        assertTrue(FixtureLifecycleAnalyzer.preflight(SOURCE, TEST.replace("import org.mockito.Spy;", "import custom.Spy;")).isEmpty());
        assertTrue(FixtureLifecycleAnalyzer.preflight(SOURCE, TEST.replace("@ExtendWith(MockitoExtension.class)", "")).isEmpty());
        assertTrue(FixtureLifecycleAnalyzer.preflight(SOURCE, TEST.replace("import org.junit.jupiter.api.extension.ExtendWith;", "import custom.ExtendWith;")).isEmpty());
        assertTrue(FixtureLifecycleAnalyzer.preflight(SOURCE, TEST.replace("import org.mockito.Spy;", "import other.Subject; import org.mockito.Spy;")).isEmpty());
        assertTrue(FixtureLifecycleAnalyzer.preflight("package other; class Subject { Subject(Object value) {} }", TEST).isEmpty());
    }

    public void testPreflightUnderstandsRequiredArgsAndNoArgsLombokDeclarations() {
        String lombok = "package sample; @lombok.RequiredArgsConstructor class Subject { final Repository repository; }";
        assertEquals(1, FixtureLifecycleAnalyzer.preflight(lombok, TEST).size());
        assertTrue(FixtureLifecycleAnalyzer.preflight(lombok.replace("@lombok.RequiredArgsConstructor", "@lombok.NoArgsConstructor @lombok.RequiredArgsConstructor"), TEST).isEmpty());
        assertTrue(FixtureLifecycleAnalyzer.preflight(SOURCE.replace("class Subject", "@lombok.NoArgsConstructor class Subject"), TEST).isEmpty());
    }

    public void testManualOpenMocksOrderingAndDeferredLambdaAreDifferent() {
        String manual = TEST.replace("@ExtendWith(MockitoExtension.class)", "").replace("service = new Subject(repository);", "org.mockito.MockitoAnnotations.openMocks(this); service = new Subject(repository);");
        assertEquals(1, FixtureLifecycleAnalyzer.preflight(SOURCE, manual).size());
        assertTrue(FixtureLifecycleAnalyzer.preflight(SOURCE, manual.replace("org.mockito.MockitoAnnotations.openMocks(this); service = new Subject(repository);", "service = new Subject(repository); org.mockito.MockitoAnnotations.openMocks(this);")).isEmpty());
        assertTrue(FixtureLifecycleAnalyzer.preflight(SOURCE, manual.replace("org.mockito.MockitoAnnotations.openMocks(this);", "Runnable later = () -> org.mockito.MockitoAnnotations.openMocks(this);")).isEmpty());
    }

    public void testRuntimeInitializationProofHasPhaseFieldAndRepairCapability() {
        var diagnostic = failure();
        MockitoRuntimeDiagnosticEnricher.enrich(List.of(diagnostic), TEST);
        assertEquals(FixtureLifecycleAnalyzer.CATEGORY, diagnostic.getCategory());
        assertEquals(RuntimeFailurePhase.TEST_SETUP, diagnostic.getFailurePhase());
        assertEquals("service", diagnostic.getFixtureField());
        assertFalse(diagnostic.getLifecycleEvidence().isEmpty());
        assertTrue(FixtureLifecycleAnalyzer.allowsSharedFixtureRepair(List.of(diagnostic)));
        assertTrue(diagnostic.getSuggestion().contains("测试方法尚未执行"));
    }

    public void testReflectionFailureInTestBodyAndConstructorBodyAreNotReclassified() {
        var body = failure(); body.setExceptionType("java.lang.NoSuchMethodException");
        FixtureLifecycleAnalyzer.enrich(body, TEST); assertEquals("TEST_RUNTIME_FAILURE", body.getCategory());
        var constructor = failure(); constructor.setStackTrace(java.util.stream.Stream.concat(constructor.getStackTrace().stream(), java.util.stream.Stream.of("at sample.Subject.<init>(Subject.java:4)")).toList());
        FixtureLifecycleAnalyzer.enrich(constructor, TEST); assertEquals("TEST_RUNTIME_FAILURE", constructor.getCategory());
        var otherField = failure(); otherField.setMessage(otherField.getMessage().replace("'service'", "'other'"));
        FixtureLifecycleAnalyzer.enrich(otherField, TEST); assertEquals("TEST_RUNTIME_FAILURE", otherField.getCategory());
        var otherConstructor = failure(); otherConstructor.setStackTrace(otherConstructor.getStackTrace().stream().map(f -> f.replace("sample.Subject.<init>()", "sample.Other.<init>()")).toList());
        FixtureLifecycleAnalyzer.enrich(otherConstructor, TEST); assertEquals("TEST_RUNTIME_FAILURE", otherConstructor.getCategory());
        var custom = failure(); FixtureLifecycleAnalyzer.enrich(custom, TEST.replace("import org.mockito.Spy;", "import custom.Spy;"));
        assertEquals("TEST_RUNTIME_FAILURE", custom.getCategory());
        var spoof = failure(); spoof.setCategory(FixtureLifecycleAnalyzer.CATEGORY);
        assertFalse(FixtureLifecycleAnalyzer.allowsSharedFixtureRepair(List.of(spoof)));
        var insideTest = failure(); insideTest.setStackTrace(java.util.stream.Stream.concat(insideTest.getStackTrace().stream(), java.util.stream.Stream.of("at sample.SubjectTest.fails(SubjectTest.java:8)")).toList());
        FixtureLifecycleAnalyzer.enrich(insideTest, TEST); assertEquals("TEST_RUNTIME_FAILURE", insideTest.getCategory());
        var noFrameworkFrame = failure(); noFrameworkFrame.setStackTrace(List.of("Caused by: java.lang.NoSuchMethodException: sample.Subject.<init>()"));
        FixtureLifecycleAnalyzer.enrich(noFrameworkFrame, TEST); assertEquals("TEST_RUNTIME_FAILURE", noFrameworkFrame.getCategory());
    }

    private static RuntimeDiagnostic failure() {
        var d = new RuntimeDiagnostic(); d.setTestId("sample.SubjectTest#fails()"); d.setCategory("TEST_RUNTIME_FAILURE");
        d.setExceptionType("org.mockito.exceptions.base.MockitoException");
        d.setMessage("Unable to initialize @Spy annotated field 'service'. Please ensure that the type 'Subject' has a no-arg constructor.");
        d.setCauseChain(List.of("org.mockito.exceptions.base.MockitoException", "java.lang.NoSuchMethodException"));
        d.setStackTrace(List.of("at org.mockito.internal.configuration.SpyAnnotationEngine.process(SpyAnnotationEngine.java:70)",
                "at org.mockito.junit.jupiter.MockitoExtension.beforeEach(MockitoExtension.java:160)", "Caused by: java.lang.NoSuchMethodException: sample.Subject.<init>()"));
        return d;
    }
}
