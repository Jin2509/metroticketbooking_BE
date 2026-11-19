package com.metroticket_api.chatbot;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.metroticket_api.ai.GeminiProps;

import tools.jackson.databind.JsonNode;

/**
 * Gọi Gemini generateContent kèm khai báo hàm.
 * Khoá API chỉ nằm ở Backend. Lỗi I/O, 408, 429 và 5xx được retry có jitter;
 * lỗi tham số/quyền không hợp lệ không retry, để ChatbotService chuyển fallback.
 */
@Component
public class GeminiChatClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatClient.class);
    private static final double TEMPERATURE = 0.2;

    public static class ChatException extends Exception {
        public ChatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final GeminiProps props;
    private final RestClient http;

    public GeminiChatClient(GeminiProps props) {
        this.props = props;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        factory.setReadTimeout(props.readTimeout());
        this.http = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    public boolean available() {
        return props.configured();
    }

    /** @return nội dung (content) của ứng viên đầu tiên, gồm các part text hoặc functionCall */
    public JsonNode generate(String systemPrompt, List<?> contents, List<Map<String, Object>> functions) throws ChatException {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", contents,
                "tools", List.of(Map.of("functionDeclarations", functions)),
                "generationConfig", Map.of("temperature", TEMPERATURE));

        int maxAttempts = Math.max(1, Math.min(props.maxAttempts(), 5));
        Duration retryBackoff = props.retryBackoff().isNegative()
                ? Duration.ZERO
                : props.retryBackoff();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                JsonNode response = http.post().uri("/models/{model}:generateContent", props.model())
                        .header("x-goog-api-key", props.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                JsonNode content = response == null ? null : response.path("candidates").path(0).path("content");
                if (content == null || content.isMissingNode() || !content.path("parts").isArray()) {
                    throw new ChatException("Gemini không trả về nội dung", null);
                }
                return content;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (!shouldRetry(status) || attempt == maxAttempts) {
                    log.warn("Gọi Gemini thất bại với HTTP {}: {}", status, e.getMessage());
                    throw new ChatException("Gọi Gemini thất bại", e);
                }
                log.warn("Gemini HTTP {}; thử lại lần {}/{}", status, attempt + 1, maxAttempts);
                sleepBeforeRetry(attempt, retryBackoff);
            } catch (RestClientException e) {
                if (attempt == maxAttempts) {
                    log.warn("Gọi Gemini thất bại sau {} lần thử: {}", attempt, e.getMessage());
                    throw new ChatException("Gọi Gemini thất bại", e);
                }
                log.warn("Gọi Gemini I/O lỗi; thử lại lần {}/{}", attempt + 1, maxAttempts);
                sleepBeforeRetry(attempt, retryBackoff);
            } catch (ChatException e) {
                throw e;
            }
        }
        throw new ChatException("Gọi Gemini thất bại", null);
    }

    static boolean shouldRetry(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private void sleepBeforeRetry(int completedAttempts, Duration retryBackoff) throws ChatException {
        long baseMillis = retryBackoff.toMillis() * completedAttempts;
        long jitterMillis = ThreadLocalRandom.current().nextLong(0, 250);
        long delayMillis = Math.max(0, baseMillis) + jitterMillis;
        if (delayMillis == 0) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatException("Request Gemini bị ngắt khi chờ retry", e);
        }
    }
}
