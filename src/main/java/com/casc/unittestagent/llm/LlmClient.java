package com.casc.unittestagent.llm;

/**
 * 大模型补全能力的抽象接口。
 * 默认流程不强依赖大模型，该接口用于把可选远程能力与本地生成管线解耦。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public interface LlmClient {
    /**
     * 执行一次单元测试生成请求。
     *
     * @param request 发送给大模型服务的结构化上下文
     * @return 大模型返回的 Mock 策略和测试代码
     * @throws Exception 当具体客户端实现请求失败时抛出
     */
    LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) throws Exception;

    /**
     * 根据真实编译诊断修复完整测试类。
     */
    default LlmGenerationResponse repairUnitTest(LlmGenerationRequest request) throws Exception {
        return generateUnitTest(request);
    }

    /**
     * 仅生成 Mock 策略候选。
     */
    default LlmGenerationResponse generateMockStrategy(LlmGenerationRequest request) throws Exception {
        throw new UnsupportedOperationException("当前大模型客户端不支持单独生成 Mock 策略。");
    }

    default int promptLength(LlmGenerationRequest request) {
        return -1;
    }
}
