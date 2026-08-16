package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/** 候选测试运行阶段的结构化失败信息。 */
public class RuntimeDiagnostic {
    private String testId = "";
    private String exceptionType = "";
    private String message = "";
    /** JUnit XML failure 节点的完整文本；message 属性往往会丢失 Mockito 的 Wanted/But was 证据。 */
    private String failureDetails = "";
    private List<String> stackTrace = new ArrayList<>();
    private String category = "";
    private int sourceLine = -1;
    private String sourceSnippet = "";
    private String suggestion = "";
    /** 普通断言失败中的期望值；与异常类型契约分开，避免把任意值误判为类名。 */
    private String expectedValue = "";
    /** 普通断言失败中的实际值。 */
    private String actualValue = "";
    private String expectedExceptionType = "";
    private String actualExceptionType = "";
    private String rootCauseType = "";
    private List<String> causeChain = new ArrayList<>();
    /** Mockito verify 期望调用次数；-1 表示诊断中未提供。 */
    private int expectedInvocationCount = -1;
    /** Mockito verify 实际调用次数；-1 表示诊断中未提供。 */
    private int actualInvocationCount = -1;
    /** 首个与失败测试方法相关的栈帧。 */
    private String testFrame = "";
    /** 首个非 JUnit/Mockito/JDK 的生产代码栈帧。 */
    private String productionFrame = "";
    /** 根因附近最后一个可用项目栈帧。 */
    private String rootCauseFrame = "";

    public String getTestId() { return testId; }
    public void setTestId(String testId) { this.testId = testId == null ? "" : testId; }
    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType == null ? "" : exceptionType; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message == null ? "" : message; }
    public String getFailureDetails() { return failureDetails; }
    public void setFailureDetails(String value) { failureDetails = value == null ? "" : value; }
    public List<String> getStackTrace() { return stackTrace; }
    public void setStackTrace(List<String> stackTrace) { this.stackTrace = stackTrace == null ? new ArrayList<>() : stackTrace; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category == null ? "" : category; }
    public int getSourceLine() { return sourceLine; }
    public void setSourceLine(int sourceLine) { this.sourceLine = sourceLine; }
    public String getSourceSnippet() { return sourceSnippet; }
    public void setSourceSnippet(String sourceSnippet) { this.sourceSnippet = sourceSnippet == null ? "" : sourceSnippet; }
    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion == null ? "" : suggestion; }
    public String getExpectedValue() { return expectedValue; }
    public void setExpectedValue(String value) { expectedValue = value == null ? "" : value; }
    public String getActualValue() { return actualValue; }
    public void setActualValue(String value) { actualValue = value == null ? "" : value; }
    public String getExpectedExceptionType() { return expectedExceptionType; }
    public void setExpectedExceptionType(String value) { expectedExceptionType = value == null ? "" : value; }
    public String getActualExceptionType() { return actualExceptionType; }
    public void setActualExceptionType(String value) { actualExceptionType = value == null ? "" : value; }
    public String getRootCauseType() { return rootCauseType; }
    public void setRootCauseType(String value) { rootCauseType = value == null ? "" : value; }
    public List<String> getCauseChain() { return causeChain; }
    public void setCauseChain(List<String> value) { causeChain = value == null ? new ArrayList<>() : value; }
    public int getExpectedInvocationCount() { return expectedInvocationCount; }
    public void setExpectedInvocationCount(int value) { expectedInvocationCount = value < 0 ? -1 : value; }
    public int getActualInvocationCount() { return actualInvocationCount; }
    public void setActualInvocationCount(int value) { actualInvocationCount = value < 0 ? -1 : value; }
    public String getTestFrame() { return testFrame; }
    public void setTestFrame(String value) { testFrame = value == null ? "" : value; }
    public String getProductionFrame() { return productionFrame; }
    public void setProductionFrame(String value) { productionFrame = value == null ? "" : value; }
    public String getRootCauseFrame() { return rootCauseFrame; }
    public void setRootCauseFrame(String value) { rootCauseFrame = value == null ? "" : value; }
}
