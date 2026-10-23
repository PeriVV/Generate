package com.casc.unittestagent.generator;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

public class SourceImportPreflightTest extends LightJavaCodeInsightFixtureTestCase {
    private PsiFile source;
    @Override protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("package sample.api; public interface Gateway { String read(); }");
        myFixture.addClass("package sample.other; public interface Gateway { int read(); }");
        source = myFixture.configureByText("Subject.java", "package sample.app; import sample.api.Gateway; public class Subject { Gateway gateway; }");
    }
    public void testAddsOnlyExactSourceProvenTypeAndIsIdempotent() {
        String test = "package sample.app; class SubjectTest { Gateway gateway; void test() { Gateway local = gateway; } }";
        var result = SourceImportPreflight.prepare(getProject(), source, test);
        assertTrue(result.code(), result.changed()); assertEquals(1, result.imports().size());
        assertTrue(result.code().contains("import sample.api.Gateway;"));
        assertTrue(result.code().contains("Gateway local = gateway;"));
        assertFalse(SourceImportPreflight.prepare(getProject(), source, result.code()).changed());
    }
    public void testExistingExplicitBindingsLocalTypesAndTypeParametersWin() {
        for (String test : java.util.List.of(
                "package sample.app; import sample.other.Gateway; class T { Gateway value; }",
                "package sample.app; class T<Gateway> { Gateway value; }",
                "package sample.app; class T { interface Gateway {} Gateway value; }",
                "package sample.app; class T { sample.api.Gateway value; }")) {
            assertEquals(test, SourceImportPreflight.prepare(getProject(), source, test).code());
        }
    }
    public void testUnknownAndConflictingWildcardTypesAreNotGuessed() {
        for (String test : java.util.List.of("package sample.app; class T { InventedGateway value; }",
                "package sample.app; import sample.other.*; class T { Gateway value; }"))
            assertEquals(test, SourceImportPreflight.prepare(getProject(), source, test).code());
    }
    public void testInheritedMemberTypeBindingIsNotReplacedByAProductionImport() {
        myFixture.addClass("package sample.app; public class Base { public interface Gateway {} }");
        String test = "package sample.app; class T extends Base { Gateway value; }";
        assertEquals(test, SourceImportPreflight.prepare(getProject(), source, test).code());
    }
    public void testSamePackageAndJavaLangBindingsAreNotReplaced() {
        String samePackage = "package sample.api; class T { Gateway value; }";
        assertEquals(samePackage, SourceImportPreflight.prepare(getProject(), source, samePackage).code());
        myFixture.addClass("package sample.api; public class String {}");
        var withString = myFixture.configureByText("StringSubject.java", "package sample.app; import sample.api.String; class StringSubject { String value; }");
        String test = "package sample.app; class T { String value; }";
        assertEquals(test, SourceImportPreflight.prepare(getProject(), withString, test).code());
    }
    public void testImportOnlyPatchPreservesUsedExplicitStaticImports() {
        String before = "import static org.mockito.Mockito.mock; class T { Gateway gateway = mock(Gateway.class); }";
        String after = "import sample.api.Gateway; class T { Gateway gateway = mock(Gateway.class); }";
        var result = SourceImportPreflight.preserveImportOnlyPatch(before, after);
        assertTrue(result.changed()); assertTrue(result.code().contains("import static org.mockito.Mockito.mock;"));
        assertTrue(result.code().contains("import sample.api.Gateway;"));
        assertFalse(SourceImportPreflight.preserveImportOnlyPatch(result.code(), result.code()).changed());
    }
    public void testWildcardImportsCanBeRecoveredWithoutChangingBodies() {
        String before = "import static org.mockito.Mockito.*; class T { void run() { mock(Gateway.class); } }";
        String after = "import sample.api.Gateway; class T { void run() { mock(Gateway.class); } }";
        var result = SourceImportPreflight.preserveImportOnlyPatch(before, after);
        assertTrue(result.changed()); assertTrue(result.code().contains("import static org.mockito.Mockito.*;"));
    }
    public void testSemanticEditsAndExplicitRebindingAreNotMergedByImportOnlyRule() {
        String before = "import static old.Factory.mock; class T { Object run() { return mock(Gateway.class); } }";
        String changedBody = "class T { Object run() { return other(Gateway.class); } }";
        assertEquals(changedBody, SourceImportPreflight.preserveImportOnlyPatch(before, changedBody).code());
        String rebound = "import static newfactory.Factory.mock; class T { Object run() { return mock(Gateway.class); } }";
        assertEquals(rebound, SourceImportPreflight.preserveImportOnlyPatch(before, rebound).code());
    }
    public void testUnusedImportsMalformedCodeAndConflictingNewWildcardAreLeftAlone() {
        String before = "import missing.Unused; class T {}";
        assertEquals("class T {}", SourceImportPreflight.preserveImportOnlyPatch(before, "class T {}").code());
        assertEquals("class T {}", SourceImportPreflight.preserveImportOnlyPatch("import static unused.Factory.*; class T {}", "class T {}").code());
        assertEquals("class T {", SourceImportPreflight.preserveImportOnlyPatch(before, "class T {").code());
        String oldStar = "import static old.Factory.*; class T { Object run() { return mock(Gateway.class); } }";
        String newStar = "import static newer.Factory.*; class T { Object run() { return mock(Gateway.class); } }";
        assertEquals(newStar, SourceImportPreflight.preserveImportOnlyPatch(oldStar, newStar).code());
    }
}
