package com.casc.unittestagent.coverage;

import com.casc.unittestagent.generator.RepairCandidateRejectedException;
import com.casc.unittestagent.model.RuntimeDiagnostic;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class MockWiringAnalyzerTest {
    private static final String PREFIX = """
            package sample;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.BeforeEach;
            import org.junit.jupiter.api.extension.ExtendWith;
            import org.mockito.junit.jupiter.MockitoExtension;
            import org.mockito.Mock;
            import org.mockito.InjectMocks;
            import static org.mockito.Mockito.*;
            import static org.junit.jupiter.api.Assertions.*;
            @ExtendWith(MockitoExtension.class)
            class SubjectTest {
              @Mock java.util.Map<String, String> entries;
              @InjectMocks Subject subject;
              @BeforeEach void setup() { subject = new Subject(); }
              @Test void deletes() {
                subject.remove("item");
                verify(entries).remove("item");
              }
            }
            """;
    private static final String MUTABLE = """
            package sample;
            class Subject {
              java.util.Map<String, String> entries = new java.util.HashMap<>();
              public void remove(String key) { entries.remove(key); }
            }
            """;
    private static final String OWNED = MUTABLE.replace("java.util.Map<String, String> entries", "private final java.util.Map<String, String> entries");
    @Test public void overwrittenInjectMocksHasNarrowSourceProof() {
        var findings = MockWiringAnalyzer.preflight(List.of(MUTABLE), PREFIX);
        assertEquals(findings.toString(), 1, findings.size()); assertEquals(MockWiringAnalyzer.WIRING, findings.get(0).getCode());
        var diagnostic = failure(PREFIX, "entries");
        MockWiringAnalyzer.enrich(List.of(MUTABLE), PREFIX, List.of(diagnostic));
        assertEquals(MockWiringAnalyzer.WIRING, diagnostic.getCategory());
        assertTrue(MockWiringAnalyzer.allowsWiringRepair(List.of(diagnostic), PREFIX));
        MockWiringAnalyzer.validateWiringRepair(PREFIX, PREFIX.replace("subject = new Subject();", ""), List.of(diagnostic));
    }
    @Test public void finalSelfOwnedMapRequiresOracleRedesignNotFixtureBypass() {
        var d = failure(PREFIX, "entries"); MockWiringAnalyzer.enrich(List.of(OWNED), PREFIX, List.of(d));
        assertEquals(MockWiringAnalyzer.INTERNAL, d.getCategory());
        assertFalse(MockWiringAnalyzer.allowsWiringRepair(List.of(d), PREFIX));
        assertTrue(d.getSuggestion().contains("公开入口"));
    }
    @Test public void localMockReturnedByDisconnectedContainerIsAlsoDetected() {
        String source = OWNED.replace("java.util.Map<String, String>", "java.util.Map<String, java.util.Map<String, String>>");
        String test = PREFIX.replace("java.util.Map<String, String> entries", "java.util.Map<String, java.util.Map<String, String>> entries")
                .replace("subject.remove(\"item\");", "java.util.Map<String, String> bucket = mock(java.util.Map.class); when(entries.get(\"scope\")).thenReturn(bucket); subject.remove(\"item\");")
                .replace("verify(entries)", "verify(bucket)");
        var d = failure(test, "bucket"); MockWiringAnalyzer.enrich(List.of(source), test, List.of(d));
        assertEquals(MockWiringAnalyzer.INTERNAL, d.getCategory());
    }
    @Test public void inheritedOwnedStateInTestAdapterIsDetected() {
        String source = OWNED.replace("class Subject", "abstract class Base");
        String test = PREFIX.replace("@InjectMocks Subject subject;", "@InjectMocks Adapter subject;")
                .replace("new Subject()", "new Adapter()")
                .replace("  @Mock", "  static class Adapter extends Base {}\n  @Mock");
        assertTrue(MockWiringAnalyzer.preflight(List.of(source), test).stream().anyMatch(d -> d.getCode().equals(MockWiringAnalyzer.INTERNAL)));
    }
    @Test public void correctlyInjectedOrManuallyConstructedDependenciesAreAccepted() {
        assertTrue(MockWiringAnalyzer.preflight(List.of(MUTABLE), PREFIX.replace("subject = new Subject();", "")).isEmpty());
        String constructor = MUTABLE.replace("public void remove", "Subject(java.util.Map<String, String> entries) { this.entries = entries; } public void remove");
        assertTrue(MockWiringAnalyzer.preflight(List.of(constructor), PREFIX.replace("@InjectMocks ", "").replace("new Subject()", "new Subject(entries)")).isEmpty());
    }
    @Test public void differentImportedTypeAndShadowedMockAreNotProof() {
        assertTrue(MockWiringAnalyzer.preflight(List.of(OWNED), PREFIX.replace("import org.mockito.Mock;", "import other.Mock;")).isEmpty());
        assertTrue(MockWiringAnalyzer.preflight(List.of(OWNED), PREFIX.replace("subject.remove(\"item\");", "java.util.Map<String, String> entries = mock(java.util.Map.class); subject.remove(\"item\");")).isEmpty());
        assertTrue(MockWiringAnalyzer.preflight(List.of(OWNED), PREFIX.replace("import org.mockito.InjectMocks;", "import other.Subject; import org.mockito.InjectMocks;")).isEmpty());
    }
    @Test public void sourceLocationAndActualExceptionAreRequired() {
        var d = failure(PREFIX, "entries"); d.setSourceLine(d.getSourceLine()+1);
        MockWiringAnalyzer.enrich(List.of(MUTABLE), PREFIX, List.of(d)); assertEquals("MOCKITO_WANTED_BUT_NOT_INVOKED", d.getCategory());
        d = failure(PREFIX, "entries"); d.setExceptionType("java.lang.AssertionError");
        MockWiringAnalyzer.enrich(List.of(MUTABLE), PREFIX, List.of(d)); assertTrue(d.getMockWiringEvidence().isEmpty());
    }
    @Test public void unrelatedFailureCannotBorrowSharedFixtureAuthorization() {
        var d = failure(PREFIX,"entries"); MockWiringAnalyzer.enrich(List.of(MUTABLE),PREFIX,List.of(d));
        var unrelated = new RuntimeDiagnostic(); unrelated.setCategory("ASSERTION_VALUE_MISMATCH");
        assertFalse(MockWiringAnalyzer.allowsWiringRepair(List.of(d,unrelated),PREFIX));
        assertFalse(MockWiringAnalyzer.allowsWiringRepair(List.of(d),PREFIX+"\n"));
    }
    @Test public void wiringProofNeverAuthorizesInputStubOracleOrOtherFixtureChanges() {
        var d = failure(PREFIX,"entries"); MockWiringAnalyzer.enrich(List.of(MUTABLE),PREFIX,List.of(d));
        String fixed = PREFIX.replace("subject = new Subject();", "");
        for(String changed : List.of(fixed.replace("subject.remove(\"item\")", "subject.remove(\"other\")"),
                fixed.replace("verify(entries).remove(\"item\");", ""), fixed.replace("void setup() {", "void setup() { when(entries.size()).thenReturn(7);"))) {
            assertThrows(RepairCandidateRejectedException.class, () -> MockWiringAnalyzer.validateWiringRepair(PREFIX,changed,List.of(d)));
        }
    }
    @Test public void realObjectVerificationIsRejectedWithoutGuessingBusinessIntent() {
        String test = PREFIX.replace("subject.remove(\"item\");", "java.util.List<String> value = new java.util.ArrayList<>(); subject.remove(\"item\");")
                .replace("verify(entries).remove(\"item\")", "verify(value).clear()");
        assertTrue(MockWiringAnalyzer.preflight(List.of(MUTABLE), test).stream().anyMatch(d -> MockWiringAnalyzer.INTERNAL.equals(d.getCode())));
    }
    @Test public void unknownSettersHelpersAndConditionalConstructionDoNotGrantWiringRepair() {
        for(String test : List.of(PREFIX.replace("subject = new Subject();", "subject = new Subject(); subject.setEntries(entries);"),
                PREFIX.replace("subject = new Subject();", "if (true) subject = new Subject();"))) {
            var d=failure(test,"entries"); MockWiringAnalyzer.enrich(List.of(MUTABLE),test,List.of(d));
            assertFalse(MockWiringAnalyzer.allowsWiringRepair(List.of(d),test));
        }
    }
    @Test public void explicitMethodParameterIsARealConnectionEvenWithSameNamedPrivateField() {
        String test=PREFIX.replace("subject.remove(\"item\");", "subject.consume(entries); subject.remove(\"item\");");
        assertTrue(MockWiringAnalyzer.preflight(List.of(OWNED),test).isEmpty());
    }
    @Test public void differentDependencyImportsMustNotBeMatchedBySimpleName() {
        String source=MUTABLE.replace("package sample;", "package sample; import first.Dependency;")
                .replace("java.util.Map<String, String>","Dependency");
        String test=PREFIX.replace("package sample;","package sample; import second.Dependency;")
                .replace("java.util.Map<String, String>","Dependency");
        assertTrue(MockWiringAnalyzer.preflight(List.of(source),test).isEmpty());
    }
    @Test public void staleEvidenceAndAdditionalSubjectAssignmentDoNotAuthorizeRemoval() {
        var d=failure(PREFIX,"entries"); MockWiringAnalyzer.enrich(List.of(MUTABLE),PREFIX,List.of(d));
        assertThrows(RepairCandidateRejectedException.class, () -> MockWiringAnalyzer.validateWiringRepair(PREFIX+"\n",PREFIX.replace("subject = new Subject();", ""),List.of(d)));
        String test=PREFIX.replace("subject.remove(\"item\");", "subject = new Subject(); subject.remove(\"item\");");
        assertTrue(MockWiringAnalyzer.preflight(List.of(MUTABLE),test).isEmpty());
    }
    @Test public void verificationOfExplicitRealStubReturnIsRejected() {
        String test=PREFIX.replace("subject.remove(\"item\");", "when(entries.get(\"key\")).thenReturn(new String(\"value\")); subject.remove(\"item\");")
                .replace("verify(entries).remove(\"item\")","verify(entries.get(\"key\")).isEmpty()");
        assertTrue(MockWiringAnalyzer.preflight(List.of(MUTABLE),test).stream().anyMatch(d -> MockWiringAnalyzer.INTERNAL.equals(d.getCode())));
    }
    @Test public void generatedCoverageCandidateIsStoppedBeforeCompilerOnWiringEvidence() {
        try(var artifact=new TestSandboxCompiler().compileGeneratedCandidate(null,null,PREFIX,"sample.Subject",List.of(MUTABLE))) {
            assertFalse(artifact.isSuccessful());
            assertEquals(MockWiringAnalyzer.WIRING,artifact.getDiagnostics().get(0).getCode());
            assertEquals(com.casc.unittestagent.model.ValidationStage.SOURCE_PREFLIGHT,artifact.getDiagnostics().get(0).getValidationStage());
        }
    }
    @Test public void explicitlyConstructingWithAnotherMockIsDetectedWithoutGrantingInputChanges() {
        String source="package sample; class Subject { private final java.util.Map<String,String> entries; Subject(java.util.Map<String,String> entries) { this.entries=entries; } void remove(String key){ entries.remove(key); } }";
        String test=PREFIX.replace("@InjectMocks Subject subject;","Subject subject;")
                .replace("new Subject()","new Subject(mock(java.util.Map.class))");
        var d=failure(test,"entries"); MockWiringAnalyzer.enrich(List.of(source),test,List.of(d));
        assertEquals(MockWiringAnalyzer.WIRING,d.getCategory());
        assertFalse(MockWiringAnalyzer.allowsWiringRepair(List.of(d),test));
        assertThrows(RepairCandidateRejectedException.class, () -> MockWiringAnalyzer.validateWiringRepair(test,test.replace("mock(java.util.Map.class)","entries"),List.of(d)));
    }
    @Test public void sameSimpleClassNameCannotBorrowAnotherOwnersWiringEvidence() {
        var d=failure(PREFIX,"entries"); d.setTestId("other.SubjectTest#deletes()");
        MockWiringAnalyzer.enrich(List.of(MUTABLE),PREFIX,List.of(d));
        assertTrue(d.getMockWiringEvidence().isEmpty());
        assertFalse(MockWiringAnalyzer.allowsWiringRepair(List.of(d),PREFIX));
    }
    @Test public void nestedAdapterMayReallyUseTheOuterMockAndMustNotBeCalledDisconnected() {
        String test=PREFIX.replace("@InjectMocks Subject subject;", "@InjectMocks Adapter subject; class Adapter extends Subject { void remove(String key) { entries.remove(key); } }")
                .replace("new Subject()", "new Adapter()");
        assertTrue(MockWiringAnalyzer.preflight(List.of(OWNED),test).isEmpty());
    }
    @Test public void uniqueTypeCanConnectDifferentFieldNamesButAmbiguousTypesGrantNothing() {
        String test=PREFIX.replace("entries","storeMock");
        var d=failure(test,"storeMock"); MockWiringAnalyzer.enrich(List.of(MUTABLE),test,List.of(d));
        assertTrue(MockWiringAnalyzer.allowsWiringRepair(List.of(d),test));
        String ambiguous=test.replace("@InjectMocks Subject subject;","@Mock java.util.Map<String,String> other; @InjectMocks Subject subject;");
        d=failure(ambiguous,"storeMock"); MockWiringAnalyzer.enrich(List.of(MUTABLE),ambiguous,List.of(d));
        assertFalse(MockWiringAnalyzer.allowsWiringRepair(List.of(d),ambiguous));
    }
    private static RuntimeDiagnostic failure(String code, String receiver) {
        var call = StaticJavaParser.parse(code).findAll(MethodCallExpr.class).stream().filter(c -> c.getNameAsString().equals("verify") && c.getArgument(0).toString().equals(receiver)).findFirst().orElseThrow();
        var d = new RuntimeDiagnostic(); d.setTestId("sample.SubjectTest#deletes()");
        d.setExceptionType("org.mockito.exceptions.verification.WantedButNotInvoked"); d.setCategory("MOCKITO_WANTED_BUT_NOT_INVOKED");
        d.setMessage("Wanted but not invoked; zero interactions"); d.setSourceLine(call.getBegin().orElseThrow().line); return d;
    }
}
