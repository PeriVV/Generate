package com.casc.unittestagent.llm;

import com.casc.unittestagent.model.MockStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 大模型生成结果。
 */
public class LlmGenerationResponse {
    private MockStrategy mockStrategy = new MockStrategy();
    private String testCode;
    private List<String> warnings = new ArrayList<>();
    private String rawResponse;

    public MockStrategy getMockStrategy() {
        return mockStrategy;
    }

    public void setMockStrategy(MockStrategy mockStrategy) {
        this.mockStrategy = mockStrategy;
    }

    public String getTestCode() {
        return testCode;
    }

    public void setTestCode(String testCode) {
        this.testCode = testCode;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }
}
