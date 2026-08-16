package com.casc.unittestagent.parser;

import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.model.ProjectSymbolSnapshot;
import com.casc.unittestagent.model.TypeSymbol;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;

public class ProjectSymbolSnapshotBuilderTest extends LightJavaCodeInsightFixtureTestCase {
    public void testCollectsResolvedOverloadsAndVarArgs() {
        String source = """
                package sample;
                class OrderService {
                    private final Repository repository = new Repository();
                    String <caret>load(Long id) { return repository.join(repository.find(id)); }
                }
                class Repository {
                    String find(Long id) { return ""; }
                    String find(String id) { return ""; }
                    String join(String... values) { return ""; }
                }
                """;
        PsiFile file = myFixture.configureByText("OrderService.java", source);
        MethodModel model = new JavaSourceAnalyzer().analyze(source.replace("<caret>", ""), "load");

        ProjectSymbolSnapshot snapshot = ReadAction.compute(() ->
                new ProjectSymbolSnapshotBuilder().build(getProject(), file, myFixture.getEditor(), model));

        assertTrue(snapshot.isUsable());
        TypeSymbol repository = snapshot.getTypes().stream()
                .filter(type -> type.getQualifiedName().endsWith("Repository"))
                .findFirst()
                .orElseThrow();
        Set<String> ids = repository.getMethods().stream().map(method -> method.getId()).collect(Collectors.toSet());
        assertTrue(ids.toString(), ids.stream().anyMatch(id -> id.contains("#find(java.lang.Long)")));
        assertTrue(ids.toString(), ids.stream().anyMatch(id -> id.contains("#find(java.lang.String)")));
        assertTrue(repository.getMethods().stream()
                .anyMatch(method -> method.getName().equals("join") && method.isVarArgs()));
    }

    public void testCollectsConstructorsGenericReturnsInheritedStaticAndChainMethods() {
        String source = """
                package sample;
                class OrderService extends BaseService {
                    String <caret>load(String input) {
                        Node node = new Node(input);
                        String echoed = node.echo(input);
                        return Factory.create(echoed).child().value() + inherited(1);
                    }
                }
                class BaseService {
                    protected String inherited(Number value) { return value.toString(); }
                }
                class Factory {
                    static Node create(String value) { return new Node(value); }
                }
                class Node {
                    Node() {}
                    Node(String value) {}
                    <T> T echo(T value) { return value; }
                    Child child() { return new Child(); }
                }
                class Child {
                    String value() { return "ok"; }
                }
                """;
        PsiFile file = myFixture.configureByText("OrderService.java", source);
        MethodModel model = new JavaSourceAnalyzer().analyze(source.replace("<caret>", ""), "load");

        ProjectSymbolSnapshot snapshot = ReadAction.compute(() ->
                new ProjectSymbolSnapshotBuilder().build(getProject(), file, myFixture.getEditor(), model));

        TypeSymbol node = typeEndingWith(snapshot, "Node");
        assertTrue(node.getConstructors().stream()
                .anyMatch(constructor -> constructor.getParameterTypes().equals(List.of("java.lang.String"))));
        assertTrue(node.getMethods().stream()
                        .map(method -> method.getName() + ":" + method.getReturnType())
                        .collect(Collectors.toList()).toString(),
                node.getMethods().stream()
                .anyMatch(method -> method.getName().equals("echo")
                        && "java.lang.String".equals(method.getReturnType())));
        assertTrue(node.getMethods().stream()
                .anyMatch(method -> method.getName().equals("child")
                        && "package-private".equals(method.getVisibility())
                        && method.getReturnType().endsWith("Child")));

        TypeSymbol factory = typeEndingWith(snapshot, "Factory");
        assertTrue(factory.getMethods().stream()
                .anyMatch(method -> method.getName().equals("create") && method.isStaticMethod()));
        TypeSymbol base = typeEndingWith(snapshot, "BaseService");
        assertTrue(base.getMethods().stream()
                .anyMatch(method -> method.getName().equals("inherited")
                        && "protected".equals(method.getVisibility())));
        TypeSymbol child = typeEndingWith(snapshot, "Child");
        assertTrue(child.getMethods().stream().anyMatch(method -> method.getName().equals("value")));
    }

    public void testClassModeLocatesRequestedOverloadInsteadOfCaretOverload() {
        String source = """
                package sample;
                class OverloadedService {
                    int load(int value) { return value; }
                    String <caret>load(String value) { return value; }
                }
                """;
        PsiFile file = myFixture.configureByText("OverloadedService.java", source);
        String plain = source.replace("<caret>", "");
        MethodModel intOverload = new JavaSourceAnalyzer().analyze(plain, "load", 3);

        ProjectSymbolSnapshot snapshot = ReadAction.compute(() ->
                new ProjectSymbolSnapshotBuilder().build(getProject(), file, myFixture.getEditor(), intOverload));

        assertTrue(snapshot.isUsable());
        assertEquals("int", snapshot.getTargetMethod().getReturnType());
        assertEquals(List.of("int"), snapshot.getTargetMethod().getParameterTypes());
        assertEquals("sample.OverloadedService#load(int)", snapshot.getTargetMethod().getId());
    }

    public void testCollectsFixtureMethodsAndProjectSourceBodies() {
        String source = """
                package sample;
                class VisitService {
                    String <caret>ownerName(Pet pet) { return pet.getName(); }
                }
                class Pet {
                    private String name;
                    Pet() {}
                    void setName(String name) { this.name = name; }
                    String getName() { return name; }
                }
                """;
        PsiFile file = myFixture.configureByText("VisitService.java", source);
        MethodModel model = new JavaSourceAnalyzer().analyze(source.replace("<caret>", ""), "ownerName");

        ProjectSymbolSnapshot snapshot = ReadAction.compute(() ->
                new ProjectSymbolSnapshotBuilder().build(getProject(), file, myFixture.getEditor(), model));

        TypeSymbol pet = typeEndingWith(snapshot, "Pet");
        assertTrue(pet.getMethods().stream().anyMatch(method -> method.getName().equals("setName")
                && method.isFixtureMethod()
                && method.getSourceCode().contains("this.name = name")));
        assertTrue(snapshot.getTargetMethod().getSourceCode().contains("return pet.getName()"));
    }

    private TypeSymbol typeEndingWith(ProjectSymbolSnapshot snapshot, String name) {
        return snapshot.getTypes().stream()
                .filter(type -> type.getQualifiedName().endsWith(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + name + " in "
                        + snapshot.getTypes().stream().map(TypeSymbol::getQualifiedName).collect(Collectors.toList())
                        + ", unresolved=" + snapshot.getUnresolvedSymbols()));
    }
}
