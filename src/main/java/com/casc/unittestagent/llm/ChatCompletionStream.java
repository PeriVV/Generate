package com.casc.unittestagent.llm;

import okio.BufferedSource;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.IOException;

/** Assemble one completed choice; partial code and reasoning are never accepted as a completion. */
final class ChatCompletionStream {
    private static final int MAX_EVENT_BYTES = 1_048_576;
    private static final int MAX_CONTENT_CHARS = 4_194_304;
    private final StringBuilder content = new StringBuilder();
    private boolean reasoning;
    private String finishReason;
    interface Observer { void event(int contentChars, boolean reasoning, boolean content, String finishReason); }
    private final Observer observer;
    ChatCompletionStream() { this((chars, reasoning, content, finish) -> {}); }
    ChatCompletionStream(Observer observer) { this.observer = observer; }

    String read(BufferedSource source, Runnable checkCancelled) throws IOException {
        StringBuilder event = new StringBuilder();
        while (true) {
            checkCancelled.run();
            String line;
            try { line = source.readUtf8LineStrict(MAX_EVENT_BYTES); }
            catch (EOFException end) {
                if (source.buffer().size() > MAX_EVENT_BYTES)
                    throw new LlmResponseFormatException("大模型流式事件超过大小限制。");
                if (source.buffer().size() > 0) {
                    line = source.readUtf8();
                } else {
                    if (event.length() > 0 && consume(event.toString())) return completion();
                    throw new IOException("LLM response stream ended before finish_reason; partial output discarded.");
                }
            }
            if (line.isEmpty()) {
                if (event.length() > 0 && consume(event.toString())) return completion();
                event.setLength(0);
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (value.startsWith(" ")) value = value.substring(1);
                if (event.length() > 0) event.append('\n');
                event.append(value);
                if (event.length() > MAX_EVENT_BYTES)
                    throw new LlmResponseFormatException("大模型流式事件超过大小限制。");
            }
            // SSE comments, event names, ids and retry hints carry no completion text.
        }
    }

    private boolean consume(String data) throws IOException {
        if (data.isBlank()) return false;
        if (data.trim().equals("[DONE]"))
            throw new IOException("LLM response stream has no finish_reason; partial output discarded.");
        JSONObject chunk;
        try { chunk = new JSONObject(data); }
        catch (org.json.JSONException error) {
            throw new LlmResponseFormatException("大模型流式响应不是合法 JSON。");
        }
        if (chunk.has("error") && !chunk.isNull("error")) throw new IOException("LLM stream error: " + chunk.opt("error"));
        JSONArray choices = chunk.optJSONArray("choices");
        if (choices == null) throw new LlmResponseFormatException("大模型流式响应中没有 choices。");
        for (int i = 0; i < choices.length(); i++) {
            JSONObject choice = choices.optJSONObject(i);
            if (choice == null || choice.optInt("index", 0) != 0) continue;
            JSONObject delta = choice.optJSONObject("delta");
            JSONObject message = choice.optJSONObject("message");
            JSONObject payload = delta == null ? message : delta;
            boolean newReasoning = false, newContent = false;
            if (payload != null) {
                String text = text(payload.opt("content"));
                newContent = !text.isBlank();
                // Some MiniMax gateways send a final complete message instead of a delta.
                if (delta == null && !text.isEmpty()) content.setLength(0);
                content.append(text);
                var details = payload.optJSONArray("reasoning_details");
                newReasoning = !text(payload.opt("reasoning_content")).isBlank()
                        || details != null && details.length() > 0;
                reasoning |= newReasoning;
                if (content.length() > MAX_CONTENT_CHARS)
                    throw new LlmResponseFormatException("大模型流式输出超过大小限制。");
            }
            String finish = choice.optString("finish_reason", "");
            observer.event(content.length(), newReasoning, newContent, finish);
            if (!finish.isBlank()) { finishReason = finish; return true; }
        }
        if (choices.length() == 0) observer.event(content.length(), false, false, "");
        return false;
    }

    private String completion() {
        JSONObject message = new JSONObject().put("content", content.toString());
        if (reasoning) message.put("reasoning_content", "received");
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", finishReason).put("message", message))).toString();
    }

    static String text(Object value) {
        if (value instanceof String string) return string;
        if (value instanceof JSONArray parts) {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) result.append(text(parts.opt(i)));
            return result.toString();
        }
        if (value instanceof JSONObject part) {
            if (part.has("text")) {
                Object text = part.opt("text");
                return text instanceof JSONObject object ? object.optString("value", "") : text(text);
            }
            return text(part.opt("content"));
        }
        return "";
    }
}
