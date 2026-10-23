package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.*;
import junit.framework.TestCase;
import java.util.*;

public class SpringFixtureAnalyzerTest extends TestCase {
    static final String SOURCE = """
            package sample;
            import org.springframework.beans.factory.annotation.Value;
            public class Subject {
                @Value("${service.name}") private String name;
                @Value("${server.port:8080}") private int port;
                @Value("#{'${feature.exposed}' != 'off'}") private Boolean enabled;
                @Value("${outer:${inner:fallback}}") private String fallback;
            }
            """;
    private static final String CONTAINER = """
            package sample;
            import org.springframework.test.context.ContextConfiguration;
            import org.springframework.beans.factory.annotation.Autowired;
            @ContextConfiguration(classes=Subject.class) class SubjectTest {
                @Autowired Subject subject;
                @org.junit.jupiter.api.BeforeEach void setup() { subject = new Subject(); }
                @org.junit.jupiter.api.Test void exercise() { subject.toString(); }
            }
            """;
    public void testActualKeysDefaultsSpelAndNestedPlaceholders() {
        var properties = SpringFixtureAnalyzer.properties(List.of(SOURCE));
        assertEquals(4, properties.size());
        assertEquals("sample.Subject", properties.get(0).owner());
        assertEquals(List.of("service.name"), properties.get(0).requiredKeys());
        assertEquals("int", properties.get(1).type()); assertTrue(properties.get(1).requiredKeys().isEmpty());
        assertTrue(properties.get(2).spel()); assertEquals(List.of("feature.exposed"), properties.get(2).keys());
        assertEquals(List.of("outer", "inner"), properties.get(3).keys());
        assertTrue(properties.get(3).requiredKeys().isEmpty());
    }
    public void testConfigurationPropertiesUsesRealPrefixAndCanonicalScalarKeys() {
        var properties = SpringFixtureAnalyzer.properties(List.of("""
                package sample;
                @org.springframework.boot.context.properties.ConfigurationProperties(prefix="client.pool")
                class Options { private int maxSize; private String endpoint; }
                """));
        assertEquals(List.of("client.pool.max-size"), properties.get(0).keys());
        assertEquals(List.of("client.pool.endpoint"), properties.get(1).keys());
        assertTrue(properties.stream().allMatch(p -> p.requiredKeys().isEmpty()));
    }
    public void testSourceBackedSuperclassBindingAppliesToChildLifecycle() {
        String parent=SOURCE.replace("public class Subject", "public class Parent");
        String child="package sample; public class Subject extends Parent {}";
        assertFalse(SpringFixtureAnalyzer.preflight(List.of(parent,child),CONTAINER.replace("@Autowired", "")).isEmpty());
    }

    public void testContainerNewIsRejectedBeforeCompilation() {
        var diagnostics = SpringFixtureAnalyzer.preflight(List.of(SOURCE), CONTAINER);
        assertFalse(diagnostics.isEmpty());
        assertTrue(diagnostics.stream().allMatch(d -> d.getCode().equals("SPRING_CONTAINER_OWNERSHIP") && d.getValidationStage() == ValidationStage.SOURCE_PREFLIGHT));
    }
    public void testExplicitConstructorPropertyStateIsNotMistakenForLostFieldInjection() {
        String source="package sample; class Subject { Subject(@org.springframework.beans.factory.annotation.Value(\"${service.name}\") String name) {} }";
        String test=CONTAINER.replace("@Autowired Subject subject;", "Subject subject;").replace("new Subject()", "new Subject(\"explicit\")");
        assertTrue(SpringFixtureAnalyzer.preflight(List.of(source),test).isEmpty());
        assertEquals("PARAMETER",SpringFixtureAnalyzer.properties(List.of(source)).get(0).injectionSite());
    }
    public void testNestedThisIsNotAnAssignmentToOuterInjectedField() {
        String test=CONTAINER.replace("subject = new Subject();", "")
                .replace("@Autowired Subject subject;", "@Autowired Subject subject; class Helper { Object subject; void setup() { this.subject = new Object(); } }");
        assertTrue(SpringFixtureAnalyzer.preflight(List.of(),test).isEmpty());
    }

    public void testUnparseableAuxiliarySourceDoesNotEraseProvenInjectionOverwrite() {
        assertFalse(SpringFixtureAnalyzer.preflight(List.of("unparseable source {}"),CONTAINER).isEmpty());
    }

    public void testOtherInjectedTypesAlsoCannotBeReplaced() {
        var diagnostics = SpringFixtureAnalyzer.preflight(List.of(), CONTAINER);
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).getMessage().contains("被注入字段"));
    }
    public void testFieldInitializerAndNestedSubtypeCannotBypassOwnership() {
        String field = CONTAINER.replace("@Autowired Subject subject;", "Subject subject = new Subject();").replace("void setup() { subject = new Subject(); }", "void setup() { }");
        assertFalse(SpringFixtureAnalyzer.preflight(List.of(SOURCE), field).isEmpty());
        String nested = CONTAINER.replace("new Subject()", "new Child()").replace("@Autowired Subject subject;", "static class Child extends Subject {} @Autowired Subject subject;");
        assertFalse(SpringFixtureAnalyzer.preflight(List.of(SOURCE), nested).isEmpty());
    }
    public void testRealBeanFactoryAndExternalPropertiesAreAllowed() {
        String valid = CONTAINER.replace("subject = new Subject();", "")
                .replace("@Autowired Subject subject;", "@Autowired Subject subject; @org.springframework.context.annotation.Bean Subject bean() { return new Subject(); }");
        assertTrue(SpringFixtureAnalyzer.preflight(List.of(SOURCE), valid).isEmpty());
    }
    public void testPlainUnitConstructionAndLocalShadowAreNotInjectionOverwriteProof() {
        String plain = CONTAINER.replace("@ContextConfiguration(classes=Subject.class)", "").replace("@Autowired", "");
        assertTrue(SpringFixtureAnalyzer.preflight(List.of(SOURCE), plain).isEmpty());
        String local = CONTAINER.replace("subject = new Subject();", "Subject subject = null;");
        assertTrue(SpringFixtureAnalyzer.preflight(List.of(), local).isEmpty());
    }
    public void testNoopValueSetterIsRejectedButDifferentAnnotationIsNotSpring() {
        String code = "class T { @org.springframework.beans.factory.annotation.Value(\"${x}\") void setX(String x) {} }";
        assertEquals("SPRING_VALUE_NOOP", SpringFixtureAnalyzer.preflight(List.of(), code).get(0).getCode());
        assertTrue(SpringFixtureAnalyzer.preflight(List.of(), code.replace("org.springframework.beans.factory.annotation.Value", "example.Value")).isEmpty());
        assertTrue(SpringFixtureAnalyzer.properties(List.of(SOURCE.replace("org.springframework.beans.factory.annotation.Value", "example.Value"))).isEmpty());
    }
    public void testMissingPropertyRequiresActualSourceContractAndPrimaryRuntimeProof() {
        var diagnostic = SpringRuntimeFailureAnalyzerTest.primary("sample.T#one()", "one");
        SpringRuntimeFailureAnalyzer.enrich(List.of(diagnostic));
        SpringFixtureAnalyzer.enrich(List.of(SOURCE), List.of(diagnostic));
        assertEquals(SpringFixtureAnalyzer.BINDING_FAILURE, diagnostic.getCategory());
        assertEquals("service.name", diagnostic.getFixtureField());
        assertTrue(SpringFixtureAnalyzer.allowsPropertyRepair(List.of(diagnostic)));
        SpringRuntimeFailureAnalyzer.enrich(List.of(diagnostic));
        assertEquals(SpringFixtureAnalyzer.BINDING_FAILURE, diagnostic.getCategory());
        var unknown = SpringRuntimeFailureAnalyzerTest.primary("sample.T#one()", "one");
        unknown.setRootCauseMessage("Could not resolve placeholder 'unknown.key'");
        SpringRuntimeFailureAnalyzer.enrich(List.of(unknown)); SpringFixtureAnalyzer.enrich(List.of(SOURCE), List.of(unknown));
        assertFalse(SpringFixtureAnalyzer.allowsPropertyRepair(List.of(unknown)));
    }
    public void testPropertyRepairOnlyAddsSourceProvenKeysAndKeepsBodyAndExistingValues() {
        var d = SpringRuntimeFailureAnalyzerTest.primary("sample.T#one()", "one");
        SpringRuntimeFailureAnalyzer.enrich(List.of(d)); SpringFixtureAnalyzer.enrich(List.of(SOURCE), List.of(d));
        String before = "@org.springframework.test.context.TestPropertySource(properties={\"other=keep\"}) class T { @org.junit.jupiter.api.Test void one() { check(42); } }";
        String after = before.replace("\"other=keep\"", "\"other=keep\",\"service.name=test\"");
        SpringFixtureAnalyzer.validatePropertyRepair(before, after, List.of(d));
        for (String bad : List.of(after.replace("keep", "changed"), after.replace("check(42)", "check(0)"), after.replace("service.name", "guessed.name"), after.replace("class T", "@Deprecated class T"))) {
            try { SpringFixtureAnalyzer.validatePropertyRepair(before, bad, List.of(d)); fail("Accepted unsafe patch: " + bad); }
            catch (com.casc.unittestagent.generator.RepairCandidateRejectedException expected) { }
        }
    }
}
