package com.metroticket_api.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Gemini API (Google AI Studio). Endpoint Interactions: POST {baseUrl}/interactions,
 * header {@code x-goog-api-key} + {@code Api-Revision}. Chưa có apiKey thì mọi hồ sơ chuyển thẳng sang duyệt tay.
 */
@ConfigurationProperties(prefix = "app.ai.gemini")
public record GeminiProps(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("https://generativelanguage.googleapis.com/v1beta") String baseUrl,
        @DefaultValue("gemini-3.8-flash") String model,
        @DefaultValue("2026-05-20") String apiRevision,
        @DefaultValue("10s") Duration connectTimeout,
        /** Ảnh + suy luận có thể mất vài chục giây; worker chạy nền nên chấp nhận chờ lâu. */
        @DefaultValue("90s") Duration readTimeout,
        /** Số lần gọi lại khi Gemini lỗi tạm thời (429/5xx/timeout) trước khi chuyển duyệt tay. */
        @DefaultValue("3") int maxAttempts,
        /** Thời gian chờ trước lần thử lại thứ n = retryBackoff × n. */
        @DefaultValue("1m") Duration retryBackoff,
        @DefaultValue("1024") int maxOutputTokens) {

    public boolean configured() {
        return enabled && !apiKey.isBlank();
    }
}
