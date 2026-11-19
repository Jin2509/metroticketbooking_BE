package com.metroticket_api.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
}
