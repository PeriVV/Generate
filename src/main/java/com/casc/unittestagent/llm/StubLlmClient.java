package com.casc.unittestagent.llm;

/**
 * 大模型客户端的空实现。
 * 该实现用于本地或离线场景，保证主流程在未配置远程服务时仍然可以运行。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class StubLlmClient implements LlmClient {
    /**
     * 执行一次大模型补全请求。
     * 具体实现可以访问远程服务，也可以在离线场景返回空结果。
     *
      * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
      *
     * @param request 发送给大模型服务的提示词内容
     * @return 远程大模型返回的补全文本
     */
    @Override
    public LlmGenerationResponse generateUnitTest(LlmGenerationRequest request) {
        LlmGenerationResponse response = new LlmGenerationResponse();
        response.setTestCode("");
        return response;
    }
}
