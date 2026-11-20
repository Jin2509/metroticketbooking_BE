package com.metroticket_api.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.metroticket_api.chatbot.GeminiEmbeddingClient.EmbeddingException;

class ChatFaqSearchServiceTest {

    @Test
    void combinesFullTextAndSemanticRanks() throws Exception {
        ChatFaq lexicalOnly = faq("Vé sinh viên", "[0.6,0.8]");
        ChatFaq semanticOnly = faq("Giá vé lượt", "[1.0,0.0]");
        ChatFaq shared = faq("Vé tháng và vé 30 ngày", "[0.9,0.1]");

        ChatFaqRepository faqs = mock(ChatFaqRepository.class);
        GeminiEmbeddingClient embeddings = mock(GeminiEmbeddingClient.class);
        when(embeddings.available()).thenReturn(true);
        when(embeddings.model()).thenReturn("test-model");
        when(embeddings.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        when(faqs.search(anyString())).thenReturn(List.of(shared, lexicalOnly));
        when(faqs.searchSemantic(anyString(), eq("test-model"), eq(0.7)))
                .thenReturn(List.of(semanticOnly, shared, lexicalOnly));

        ChatFaqSearchService search = new ChatFaqSearchService(faqs, embeddings, new SimpleMeterRegistry());

        assertThat(search.search("vé tháng có hạn bao lâu"))
                .containsExactly(shared, lexicalOnly, semanticOnly);
    }

    @Test
    void fallsBackToFullTextWhenEmbeddingProviderFails() throws Exception {
        ChatFaq lexical = faq("Giá vé lượt", null);
        ChatFaqRepository faqs = mock(ChatFaqRepository.class);
        GeminiEmbeddingClient embeddings = mock(GeminiEmbeddingClient.class);
        when(embeddings.available()).thenReturn(true);
        when(embeddings.embed(anyString())).thenThrow(new EmbeddingException("provider unavailable", null));
        when(faqs.search(anyString())).thenReturn(List.of(lexical));

        ChatFaqSearchService search = new ChatFaqSearchService(faqs, embeddings, new SimpleMeterRegistry());

        assertThat(search.search("giá vé metro")).containsExactly(lexical);
    }

    @Test
    void usesFullTextWhenEmbeddingIsNotConfigured() {
        ChatFaq lexical = faq("Giá vé lượt", null);
        ChatFaqRepository faqs = mock(ChatFaqRepository.class);
        GeminiEmbeddingClient embeddings = mock(GeminiEmbeddingClient.class);
        when(embeddings.available()).thenReturn(false);
        when(faqs.search(anyString())).thenReturn(List.of(lexical));

        ChatFaqSearchService search = new ChatFaqSearchService(faqs, embeddings, new SimpleMeterRegistry());

        assertThat(search.search("giá vé metro")).containsExactly(lexical);
    }

    private static ChatFaq faq(String question, String vector) {
        ChatFaq faq = new ChatFaq("Vé", question, "Câu trả lời", null, UUID.randomUUID(), Instant.now());
        if (vector != null) {
            faq.setEmbedding(vector, "test-model");
        }
        return faq;
    }
}
