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

    public void testLocatesNestedConcreteMethodBySourceRangeWhenOuterHasSameSignature() {
        String source = """
                package sample;
                abstract class QuietAppendable {
                    abstract QuietAppendable append(char value);
                    <caret>
                    static final class StringBuilderAppendable extends QuietAppendable {
                        @Override
                        StringBuilderAppendable append(char value) {
                            return this;
                        }
                    }
                }
                """;
        PsiFile file = myFixture.configureByText("QuietAppendable.java", source);
        String plain = source.replace("<caret>", "");
        MethodModel nested = new JavaSourceAnalyzer().analyze(plain, "append", 7);

        ProjectSymbolSnapshot snapshot = ReadAction.compute(() ->
                new ProjectSymbolSnapshotBuilder().build(getProject(), file, myFixture.getEditor(), nested));

        assertTrue(snapshot.getUnresolvedSymbols().toString(), snapshot.isUsable());
        assertEquals("sample.QuietAppendable.StringBuilderAppendable", snapshot.getTargetType());
        assertEquals("sample.QuietAppendable.StringBuilderAppendable",
                snapshot.getTargetMethod().getOwnerQualifiedName());
        assertEquals("sample.QuietAppendable.StringBuilderAppendable#append(char)",
                snapshot.getTargetMethod().getId());
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

    public void testCollectsRecordCanonicalConstructorAndConcreteFixtureSubtypes() {
        String source = """
                package sample;
                class CommandService {
                    int <caret>quantity(Item item, Animal animal, Command command) {
                        return item.quantity() + animal.score();
                    }
                }
                record Item(String sku, int quantity, int unitPrice) {}
                abstract class Animal { abstract int score(); }
                final class Dog extends Animal { Dog() {} int score() { return 1; } }
                sealed interface Command permits Create, Delete {}
                record Create(String name) implements Command {}
                record Delete(long id) implements Command {}
                """;
        PsiFile file = myFixture.configureByText("CommandService.java", source);
        MethodModel model = new JavaSourceAnalyzer().analyze(source.replace("<caret>", ""), "quantity");

        ProjectSymbolSnapshot snapshot = ReadAction.compute(() ->
                new ProjectSymbolSnapshotBuilder().build(getProject(), file, myFixture.getEditor(), model));

        TypeSymbol item = typeEndingWith(snapshot, "Item");
        assertEquals("record", item.getKind());
        assertTrue(item.getConstructors().stream().anyMatch(constructor -> constructor.getParameterTypes().equals(
                List.of("java.lang.String", "int", "int"))));
        TypeSymbol animal = typeEndingWith(snapshot, "Animal");
        assertTrue(animal.isAbstractType());
        assertTrue(animal.getConcreteSubtypes().toString(),
                animal.getConcreteSubtypes().stream().anyMatch(name -> name.endsWith("Dog")));
        TypeSymbol command = typeEndingWith(snapshot, "Command");
        assertTrue(command.isSealedType());
        assertEquals(2, command.getPermittedSubtypes().size());
        assertEquals(2, command.getConcreteSubtypes().size());
    }

    public void testCollectsConstructorSignaturesForUnreferencedClassFields() {
        String source = """
                package sample;
                class AuditService {
                    private final Store store;
                    private final Meter meter;
                    String <caret>describe(int value) { return "v" + value; }
                    String flush() { return store.dump() + meter.tick(); }
                }
                class Store { Store(String name) {} String dump() { return ""; } }
                class Meter { Meter() {} Meter(String id, int size) {} String tick() { return ""; } }
                """;
        PsiFile file = myFixture.configureByText("AuditService.java", source);
        MethodModel model = new JavaSourceAnalyzer().analyze(source.replace("<caret>", ""), "describe");

        ProjectSymbolSnapshot snapshot = ReadAction.compute(() ->
                new ProjectSymbolSnapshotBuilder().build(getProject(), file, myFixture.getEditor(), model));

        TypeSymbol store = typeEndingWith(snapshot, "Store");
        assertEquals(1, store.getConstructors().size());
        assertEquals(List.of("java.lang.String"), store.getConstructors().get(0).getParameterTypes());
        // 兜底构造器只提供签名，不携带源码，控制上下文增量。
        assertTrue(store.getConstructors().get(0).getSourceCode().isEmpty());
        // 只补构造器：不收集方法，模型仍需从可达性通道获取方法签名。
        assertTrue(store.getMethods().isEmpty());
        TypeSymbol meter = typeEndingWith(snapshot, "Meter");
        assertEquals(2, meter.getConstructors().size());
    }

    public void testFieldConstructorFallbackSkipsSubtypeExpansionForInterfaces() {
        String source = """
                package sample;
                class NotifyService {
                    private final Gateway gateway;
                    String <caret>render(String body) { return body; }
                }
                interface Gateway { String send(String body); }
                class HttpGateway implements Gateway { HttpGateway() {} public String send(String body) { return body; } }
                """;
        PsiFile file = myFixture.configureByText("NotifyService.java", source);
        MethodModel model = new JavaSourceAnalyzer().analyze(source.replace("<caret>", ""), "render");

        ProjectSymbolSnapshot snapshot = ReadAction.compute(() ->
                new ProjectSymbolSnapshotBuilder().build(getProject(), file, myFixture.getEditor(), model));

        TypeSymbol gateway = typeEndingWith(snapshot, "Gateway");
        assertEquals("interface", gateway.getKind());
        // @Mock 字段由 Mockito 直接 mock 接口；兜底不做 ClassInheritorsSearch 子类型展开。
        assertTrue(gateway.getConcreteSubtypes().isEmpty());
        assertTrue(gateway.getMethods().isEmpty());
        assertTrue(snapshot.getTypes().stream()
                .noneMatch(type -> type.getQualifiedName().endsWith("HttpGateway")));
    }

    public void testFieldConstructorFallbackCappedAtTwelveExtraTypes() {
        StringBuilder source = new StringBuilder("package sample;\nclass WideService {\n");
        for (int index = 0; index < 20; index++) {
            source.append("    private final Dep").append(index).append(" dep").append(index).append(";\n");
        }
        source.append("    int <caret>run(int value) { return value; }\n}\n");
        for (int index = 0; index < 20; index++) {
            source.append("class Dep").append(index).append(" { Dep").append(index).append("() {} }\n");
        }
        String text = source.toString();
        PsiFile file = myFixture.configureByText("WideService.java", text);
        MethodModel model = new JavaSourceAnalyzer().analyze(text.replace("<caret>", ""), "run");

        ProjectSymbolSnapshot snapshot = ReadAction.compute(() ->
                new ProjectSymbolSnapshotBuilder().build(getProject(), file, myFixture.getEditor(), model));

        long depCount = snapshot.getTypes().stream()
                .filter(type -> type.getQualifiedName().startsWith("sample.Dep")).count();
        assertEquals(12, depCount);
        assertTrue(snapshot.getTypes().size() <= 40 + 12);
    }

    public void testSynthesizesLombokRequiredArgsConstructor() {
        String source = """
                package sample;
                import lombok.RequiredArgsConstructor;
                @RequiredArgsConstructor
                class PaymentService {
                    private final Ledger ledger;
                    private final String tenant;
                    private int cached;
                    private final String region = "cn";
                    int <caret>charge(int amount) { return amount; }
                }
                class Ledger { Ledger() {} }
                """;
        PsiFile file = myFixture.configureByText("PaymentService.java", source);
        MethodModel model = new JavaSourceAnalyzer().analyze(source.replace("<caret>", ""), "charge");

        ProjectSymbolSnapshot snapshot = ReadAction.compute(() ->
                new ProjectSymbolSnapshotBuilder().build(getProject(), file, myFixture.getEditor(), model));

        TypeSymbol payment = typeEndingWith(snapshot, "PaymentService");
        // 未初始化 final 依序进入；非 final 与已初始化 final 被排除。
        assertEquals(1, payment.getConstructors().size());
        assertEquals(List.of("sample.Ledger", "java.lang.String"),
                payment.getConstructors().get(0).getParameterTypes());
    }

    public void testSynthesizesLombokConstructorsForFieldTypes() {
        String source = """
                package sample;
                import lombok.AllArgsConstructor;
                import lombok.NoArgsConstructor;
                class RowService {
                    private final Row row;
                    String <caret>render() { return "r"; }
                }
                @AllArgsConstructor
                @NoArgsConstructor
                class Row {
                    private String name;
                    private final int size;
                    private static String constant;
                    private final String fixed = "x";
                }
                """;
        PsiFile file = myFixture.configureByText("RowService.java", source);
        MethodModel model = new JavaSourceAnalyzer().analyze(source.replace("<caret>", ""), "render");

        ProjectSymbolSnapshot snapshot = ReadAction.compute(() ->
                new ProjectSymbolSnapshotBuilder().build(getProject(), file, myFixture.getEditor(), model));

        TypeSymbol row = typeEndingWith(snapshot, "Row");
        assertEquals(2, row.getConstructors().size());
        // AllArgs 跳过 static 与已初始化 final；NoArgs 为空参。
        assertTrue(row.getConstructors().stream().anyMatch(constructor ->
                constructor.getParameterTypes().equals(List.of("java.lang.String", "int"))));
        assertTrue(row.getConstructors().stream().anyMatch(constructor ->
                constructor.getParameterTypes().isEmpty()));
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
