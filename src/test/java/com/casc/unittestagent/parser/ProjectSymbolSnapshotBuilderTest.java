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

    private TypeSymbol typeEndingWith(ProjectSymbolSnapshot snapshot, String name) {
        return snapshot.getTypes().stream()
                .filter(type -> type.getQualifiedName().endsWith(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + name + " in "
                        + snapshot.getTypes().stream().map(TypeSymbol::getQualifiedName).collect(Collectors.toList())
                        + ", unresolved=" + snapshot.getUnresolvedSymbols()));
    }
}
