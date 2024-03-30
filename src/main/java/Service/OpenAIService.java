package Service;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import okhttp3.Response;

import java.io.IOException;

public class OpenAIService {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final String apiKey = "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";

    public String callOpenAI(String prompt) {
        try {
            MediaType mediaType = MediaType.get("application/json; charset=utf-8");
            String json = "{\"model\": \"gpt-4\", \"prompt\": \"" + prompt + "\", \"max_tokens\": 100}";
            RequestBody body = RequestBody.create(json, mediaType);

            Request request = new Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

                return response.body().string();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
