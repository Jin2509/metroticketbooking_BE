package com.metroticket_api.chatbot;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.metroticket_api.ai.GeminiProps;

import tools.jackson.databind.JsonNode;

/**
 * Gọi Gemini embedding và trả vector 768 chiều để tìm FAQ gần nghĩa.
 * Vector được lưu dạng chuỗi trong PostgreSQL; model embedding được lưu cùng
 * để tránh dùng vector sinh bởi model khác.
 */
@Component
public class GeminiEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingClient.class);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final int OUTPUT_DIMENSIONALITY = 768;

    public static class EmbeddingException extends Exception {
        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final GeminiProps props;
    private final String model;
    private final RestClient http;

    public GeminiEmbeddingClient(GeminiProps props,
            @Value("${app.ai.gemini.embedding-model:gemini-embedding-001}") String model) {
        this.props = props;
        this.model = model;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        factory.setReadTimeout(READ_TIMEOUT);
        this.http = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    public boolean available() {
        return props.configured();
    }

    public String model() {
        return model;
    }

    @Cacheable(value = "chatbot-query-embeddings", key = "#root.target.cacheKey(#text)")
    public List<Double> embed(String text) throws EmbeddingException {
        if (!available()) {
            throw new EmbeddingException("Gemini embeddings are not configured", null);
        }
        Map<String, Object> body = Map.of(
                "model", "models/" + model,
                "content", Map.of("parts", List.of(Map.of("text", text))),
                "outputDimensionality", OUTPUT_DIMENSIONALITY);
        JsonNode response;
        try {
            response = http.post().uri("/models/{model}:embedContent", model)
                    .header("x-goog-api-key", props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException exception) {
            log.warn("Gemini embedding request failed: {}", exception.getMessage());
            throw new EmbeddingException("Gemini embedding request failed", exception);
        }
        JsonNode values = response == null ? null : response.path("embedding").path("values");
        if (values == null || !values.isArray() || values.size() != OUTPUT_DIMENSIONALITY) {
            throw new EmbeddingException("Gemini returned an embedding with an invalid dimension", null);
        }
        List<Double> vector = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            double component = value.asDouble(Double.NaN);
            if (!Double.isFinite(component)) {
                throw new EmbeddingException("Gemini returned a non-numeric embedding value", null);
            }
            vector.add(component);
        }
        return List.copyOf(vector);
    }

    public String cacheKey(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(model.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String serialize(List<Double> vector) {
        return serializeVector(vector);
    }

    static String serializeVector(List<Double> vector) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(vector.get(i));
        }
        return result.append(']').toString();
    }
}
