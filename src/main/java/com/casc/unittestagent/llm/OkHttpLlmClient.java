package com.casc.unittestagent.llm;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;

/**
 * 基于 OkHttp 调用外部大模型接口的客户端实现。
 * 该类只负责请求封装和响应读取，调用入口由环境变量控制，避免把密钥写入代码。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class OkHttpLlmClient implements LlmClient {
    /** 复用的 OkHttp 客户端实例，避免每次补全请求重复创建连接资源。 */
    private final OkHttpClient client = new OkHttpClient();

    /**
     * 执行一次大模型补全请求。
     * 具体实现可以访问远程服务，也可以在离线场景返回空结果。
     *
     * @param prompt 发送给大模型服务的提示词内容
     * @return 远程大模型返回的补全文本
     * @throws IOException 当底层操作失败时抛出
     * @throws IllegalStateException 当缺少接口地址或密钥环境变量时抛出
     */
    @Override
    public String complete(String prompt) throws IOException {
        String apiUrl = System.getenv("UNIT_TEST_AGENT_API_URL");
        String apiKey = System.getenv("UNIT_TEST_AGENT_API_KEY");

        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalStateException("UNIT_TEST_AGENT_API_URL is not configured.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("UNIT_TEST_AGENT_API_KEY is not configured.");
        }

        MediaType mediaType = MediaType.get("application/json; charset=utf-8");
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(buildJsonBody(prompt), mediaType))
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("LLM request failed: " + response.code());
            }
            return response.body() == null ? "" : response.body().string();
        }
    }

    /**
     * 构造远程大模型接口请求体。
     * 这里仅做必要 JSON 字符转义，避免提示词中的换行和引号破坏请求结构。
     */
    private String buildJsonBody(String prompt) {
        String escaped = (prompt == null ? "" : prompt)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "{\"messages\":[{\"role\":\"user\",\"content\":\"" + escaped + "\"}]}";
    }
}
