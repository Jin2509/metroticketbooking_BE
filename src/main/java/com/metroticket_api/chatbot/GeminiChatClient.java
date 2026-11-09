package com.metroticket_api.chatbot;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.metroticket_api.ai.GeminiProps;

import tools.jackson.databind.JsonNode;

/** Gọi Gemini generateContent kèm khai báo hàm. Khoá API chỉ nằm ở Backend. */
@Component
public class GeminiChatClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatClient.class);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
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
        factory.setReadTimeout(READ_TIMEOUT);
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
        JsonNode response;
        try {
            response = http.post().uri("/models/{model}:generateContent", props.model())
                    .header("x-goog-api-key", props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("Gọi Gemini thất bại: {}", e.getMessage());
            throw new ChatException("Gọi Gemini thất bại", e);
        }
        JsonNode content = response == null ? null : response.path("candidates").path(0).path("content");
        if (content == null || content.isMissingNode() || !content.path("parts").isArray()) {
            throw new ChatException("Gemini không trả về nội dung", null);
        }
        return content;
    }
}
