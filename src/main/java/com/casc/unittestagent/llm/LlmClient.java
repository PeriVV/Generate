package com.casc.unittestagent.llm;

/**
 * 大模型补全能力的抽象接口。
 * 默认流程不强依赖大模型，该接口用于把可选远程能力与本地生成管线解耦。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public interface LlmClient {
    /**
     * 执行一次大模型补全请求。
     * 具体实现可以访问远程服务，也可以在离线场景返回空结果。
     *
     * @param prompt 发送给大模型服务的提示词内容
     * @return 大模型返回的补全文本；离线实现可以返回空字符串
     * @throws Exception 当具体客户端实现请求失败时抛出
     */
    String complete(String prompt) throws Exception;
}
