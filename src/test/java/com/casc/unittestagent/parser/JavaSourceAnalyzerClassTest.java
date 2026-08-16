package com.casc.unittestagent.parser;

import com.casc.unittestagent.model.ClassModel;
import com.casc.unittestagent.model.MethodModel;
import junit.framework.TestCase;

import java.util.Set;
import java.util.stream.Collectors;

public class JavaSourceAnalyzerClassTest extends TestCase {
    public void testAnalyzesAllEligibleDeclaredMethodsAndExplainsSkippedMethods() {
        String source = """
                package sample;

                class BillingService {
                    private int value;

                    public int process(int input) { return input + 1; }
                    public String process(String input) { return input.trim(); }
                    protected void resetBusinessState() { value = 0; audit(); }
                    void packageAction() { audit(); }
                    static int add(int left, int right) { return left + right; }
                    private void audit() { }
                    public int getValue() { return value; }
                    public void setValue(int value) { this.value = value; }
                    public boolean equals(Object other) { return super.equals(other); }
                    public int hashCode() { return value; }
                    public String toString() { return Integer.toString(value); }
                }
                """;

        ClassModel model = new JavaSourceAnalyzer().analyzeClass(source, "BillingService", 4);

        assertEquals("sample.BillingService", model.getQualifiedName());
        assertEquals(5, model.getMethods().size());
        assertEquals(6, model.getSkippedMethods().size());
        Set<String> ids = model.getMethods().stream().map(MethodModel::getMethodId).collect(Collectors.toSet());
        assertTrue(ids.contains("sample.BillingService#process(int)"));
        assertTrue(ids.contains("sample.BillingService#process(String)"));
        MethodModel staticMethod = model.getMethods().stream()
                .filter(method -> method.getMethodName().equals("add")).findFirst().orElseThrow();
        assertTrue(staticMethod.isStaticMethod());
        assertTrue(model.getSkippedMethods().stream().anyMatch(skipped -> skipped.getReason().contains("getter")));
        assertTrue(model.getSkippedMethods().stream().anyMatch(skipped -> skipped.getReason().contains("private")));
        assertTrue(model.getSkippedMethods().stream().anyMatch(skipped -> skipped.getReason().contains("Object")));
    }

    public void testRejectsInterfaceAndAbstractClass() {
        try {
            new JavaSourceAnalyzer().analyzeClass("interface Service { void run(); }", "Service", 1);
            fail("interface should be rejected");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("接口"));
        }
        try {
            new JavaSourceAnalyzer().analyzeClass("abstract class Service { void run() {} }", "Service", 1);
            fail("abstract class should be rejected");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("抽象类"));
        }
        try {
            new JavaSourceAnalyzer().analyzeClass("record Value(String text) { String normalized() { return text.trim(); } }", "Value", 1);
            fail("record should be rejected");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("record"));
        }
    }

    public void testIgnoresNestedMethodsAndRejectsClassWithNoEligibleMethods() {
        String source = """
                package sample;
                class Outer {
                    public int outerWork() { return 1; }
                    class Inner {
                        public int innerWork() { return 2; }
                    }
                }
                """;

        ClassModel model = new JavaSourceAnalyzer().analyzeClass(source, "Outer", 4);

        assertEquals(1, model.getMethods().size());
        assertEquals("outerWork", model.getMethods().get(0).getMethodName());
        try {
            new JavaSourceAnalyzer().analyzeClass("""
                    class OnlyBoilerplate {
                        private int value;
                        public int getValue() { return value; }
                        public void setValue(int value) { this.value = value; }
                    }
                    """, "OnlyBoilerplate", 1);
            fail("class without eligible methods should be rejected");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("没有符合"));
        }
    }

    public void testMethodIdUsesErasedParameterTypesAndVarargsArrayForm() {
        ClassModel model = new JavaSourceAnalyzer().analyzeClass("""
                package sample;
                import java.util.List;
                class GenericService {
                    public void consume(List<String> values, String... labels) { values.size(); }
                }
                """, "GenericService", 4);

        assertEquals("sample.GenericService#consume(List,String[])",
                model.getMethods().get(0).getMethodId());
    }
}
