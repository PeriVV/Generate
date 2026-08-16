package com.casc.unittestagent.service;

import com.casc.unittestagent.model.ClassGenerationResult;
import com.casc.unittestagent.generator.ClassTestAssembler;
import com.casc.unittestagent.generator.TestDataGenerator;
import com.casc.unittestagent.model.MethodModel;
import com.casc.unittestagent.parser.JavaSourceAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 覆盖右键类生成入口的真实 PSI + 后台线程分析链路。 */
public class ClassUnitTestGenerationServicePsiTest extends LightJavaCodeInsightFixtureTestCase {
    public void testAnalyzesClassFromBackgroundTaskWithCaretInsideMethod() throws Exception {
        PsiFile file = myFixture.configureByText("BillingService.java", """
                package sample;
                class BillingService {
                    private int value;
                    public int <caret>calculate(int input) { return input + value; }
                    protected void reset() { value = 0; audit(); }
                    private void audit() { }
                }
                """);

        Future<ClassGenerationResult> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                new ClassUnitTestGenerationService().analyzeClass(
                        getProject(), file, myFixture.getEditor()));
        ClassGenerationResult result = future.get(20, TimeUnit.SECONDS);

        assertNotNull("warnings=" + result.getWarnings(), result.getClassModel());
        assertEquals("sample.BillingService", result.getClassModel().getQualifiedName());
        assertEquals(2, result.getMethodItems().size());
        assertNotNull(result.getMethodModel());
    }

    public void testFallsBackToFirstTopLevelClassWhenCaretIsOutsideClass() throws Exception {
        PsiFile file = myFixture.configureByText("BillingService.java", """
                package sample;<caret>
                class BillingService {
                    int calculate(int input) { return input + 1; }
                }
                """);

        Future<ClassGenerationResult> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                new ClassUnitTestGenerationService().analyzeClass(
                        getProject(), file, myFixture.getEditor()));
        ClassGenerationResult result = future.get(20, TimeUnit.SECONDS);

        assertNotNull("warnings=" + result.getWarnings(), result.getClassModel());
        assertEquals("BillingService", result.getClassModel().getClassName());
    }

    public void testOneMethodDataEnrichmentFailureDoesNotDiscardClassAnalysis() throws Exception {
        PsiFile file = myFixture.configureByText("BillingService.java", """
                package sample;
                class BillingService {
                    int <caret>first(int input) { return input + 1; }
                    int second(int input) { return input + 2; }
                }
                """);
        TestDataGenerator failingGenerator = new TestDataGenerator() {
            @Override
            public void enrich(MethodModel methodModel) {
                if ("second".equals(methodModel.getMethodName())) {
                    throw new IllegalArgumentException("invalid configured range");
                }
                super.enrich(methodModel);
            }
        };
        ClassUnitTestGenerationService service = new ClassUnitTestGenerationService(
                new JavaSourceAnalyzer(), failingGenerator,
                new UnitTestGenerationService(), new ClassTestAssembler());

        Future<ClassGenerationResult> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                service.analyzeClass(getProject(), file, myFixture.getEditor()));
        ClassGenerationResult result = future.get(20, TimeUnit.SECONDS);

        assertNotNull(result.getClassModel());
        assertEquals(2, result.getMethodItems().size());
        assertTrue(result.getWarnings().toString(), result.getWarnings().stream()
                .anyMatch(message -> message.contains("second") && message.contains("数据补全失败")));
    }
}
