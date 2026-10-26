package com.casc.unittestagent.llm;

import junit.framework.TestCase;
import okio.Buffer;
import org.json.JSONObject;

public class ChatCompletionStreamTest extends TestCase {
    private String read(String wire) throws Exception {
        return new ChatCompletionStream().read(new Buffer().writeUtf8(wire), () -> {});
    }

    public void testHeartbeatMultilineCrLfUsageAndOtherChoicesDoNotBecomeCode() throws Exception {
        String wire = ": keepalive\r\nevent: message\r\nid: 1\r\n\r\n"
                + "data: {\"choices\":[],\"usage\":{}}\r\n\r\n"
                + "data: {\"choices\":[\n"
                + "data: {\"index\":1,\"delta\":{\"content\":\"wrong\"}},"
                + "{\"index\":0,\"delta\":{\"reasoning_content\":\"思考\"}}]}\n\n"
                + event("{\"delta\":{\"content\":\"代码\"}}")
                + event("{\"delta\":{\"content\":[{\"text\":\"正文\"}]},\"finish_reason\":\"stop\"}");
        JSONObject message = new JSONObject(read(wire)).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        assertEquals("代码正文", message.getString("content"));
        assertTrue(message.has("reasoning_content"));
    }

    public void testFinalMiniMaxMessageReplacesDeltasInsteadOfDuplicatingText() throws Exception {
        String completion = read(event("{\"delta\":{\"content\":\"class Test\"}}")
                + event("{\"message\":{\"content\":\"class Test {}\"},\"finish_reason\":\"stop\"}"));
        assertEquals("class Test {}", new OpenAiCompatibleLlmClient(new LlmConfig()).extractMessageContent(completion));
    }

    public void testFinalEventWithoutTrailingNewlineIsAccepted() throws Exception {
        assertTrue(read("data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}").contains("done"));
    }

    public void testPrematureEofAndDoneNeverAcceptPartialCode() throws Exception {
        for (String tail : new String[]{"", "data: [DONE]\n\n"}) {
            try { read(event("{\"delta\":{\"content\":\"class ValidButPartial {}\"}}") + tail); fail(); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("partial output discarded")); }
        }
    }

    public void testLengthStopAndReasoningOnlyUseExistingCompletionChecks() throws Exception {
        var client = new OpenAiCompatibleLlmClient(new LlmConfig());
        try { client.extractMessageContent(read(event("{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}"))); fail(); }
        catch (LlmOutputTruncatedException expected) { assertTrue(expected.getMessage().contains("max_tokens")); }
        try { client.extractMessageContent(read(event("{\"delta\":{\"reasoning_content\":\"thinking\"},\"finish_reason\":\"stop\"}"))); fail(); }
        catch (LlmResponseFormatException expected) { assertTrue(expected.getMessage().contains("推理内容")); }
    }

    public void testErrorAndMalformedDataDoNotBecomeSuccessfulOutput() throws Exception {
        try { read("data: {\"error\":{\"message\":\"gateway overload\"}}\n\n"); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("gateway overload")); }
        try { read("data: not-json\n\n"); fail(); }
        catch (LlmResponseFormatException expected) { assertTrue(expected.getMessage().contains("JSON")); }
    }

    public void testOversizedEventFailsWithBoundedBuffer() throws Exception {
        try { read("data: " + "x".repeat(1_048_577)); fail(); }
        catch (LlmResponseFormatException expected) { assertTrue(expected.getMessage().contains("大小限制")); }
    }

    private String event(String choice) { return "data: {\"choices\":[" + choice + "]}\n\n"; }
}
