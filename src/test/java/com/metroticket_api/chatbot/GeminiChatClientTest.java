package com.metroticket_api.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

class GeminiChatClientTest {

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503, 504})
    void retriesTransientGeminiStatuses(int status) {
        assertThat(GeminiChatClient.shouldRetry(status)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    void doesNotRetryPermanentGeminiStatuses(int status) {
        assertThat(GeminiChatClient.shouldRetry(status)).isFalse();
    }

    @Test
    void retryClassificationHandlesClientAndServerErrorRanges() {
        assertThat(GeminiChatClient.shouldRetry(599)).isTrue();
        assertThat(GeminiChatClient.shouldRetry(499)).isFalse();
    }

    @Test
    void retryDelayUsesExponentialBackoffAndHonorsServerDelayWithinCap() {
        assertThat(GeminiChatClient.retryDelayMillis(1, Duration.ofSeconds(1), Duration.ofSeconds(10), null, 0))
                .isEqualTo(1_000);
        assertThat(GeminiChatClient.retryDelayMillis(3, Duration.ofSeconds(1), Duration.ofSeconds(10), null, 0))
                .isEqualTo(4_000);
        assertThat(GeminiChatClient.retryDelayMillis(2, Duration.ofSeconds(1), Duration.ofSeconds(10),
                Duration.ofSeconds(8), 200)).isEqualTo(8_200);
        assertThat(GeminiChatClient.retryDelayMillis(5, Duration.ofSeconds(1), Duration.ofSeconds(10),
                Duration.ofSeconds(30), 0)).isEqualTo(10_000);
        assertThat(GeminiChatClient.retryDelayMillis(2, Duration.ofSeconds(1), Duration.ofSeconds(10),
                Duration.ofSeconds(Long.MAX_VALUE), Long.MAX_VALUE)).isEqualTo(10_000);
    }
}
