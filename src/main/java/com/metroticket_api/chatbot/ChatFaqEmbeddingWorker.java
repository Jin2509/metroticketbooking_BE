package com.metroticket_api.chatbot;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.metroticket_api.chatbot.GeminiEmbeddingClient.EmbeddingException;

/**
 * Backfill embedding cho FAQ hoạt động theo lô nhỏ.
 * FAQ thay đổi/cập nhật sẽ bị đánh dấu embedding cũ bằng null trong ChatFaq
 * và worker tạo lại embedding với model hiện tại.
 */
@Component
public class ChatFaqEmbeddingWorker {

    private static final Logger log = LoggerFactory.getLogger(ChatFaqEmbeddingWorker.class);
    private static final int BATCH_SIZE = 5;

    private final ChatFaqRepository faqs;
    private final GeminiEmbeddingClient embeddings;

    public ChatFaqEmbeddingWorker(ChatFaqRepository faqs, GeminiEmbeddingClient embeddings) {
        this.faqs = faqs;
        this.embeddings = embeddings;
    }

    @Scheduled(fixedDelayString = "${app.chatbot.embedding-backfill-interval:30s}",
            initialDelayString = "${app.chatbot.embedding-backfill-interval:30s}")
    public void embedMissingFaqs() {
        if (!embeddings.available()) {
            return;
        }
        List<ChatFaq> missing = faqs.findActiveNeedingEmbedding(embeddings.model(), PageRequest.of(0, BATCH_SIZE));
        for (ChatFaq faq : missing) {
            try {
                String vector = GeminiEmbeddingClient.serialize(embeddings.embed(faq.embeddingText()));
                faqs.updateEmbedding(faq.getId(), vector, embeddings.model());
            } catch (EmbeddingException exception) {
                log.warn("Unable to generate embedding for FAQ id {}; it will be retried", faq.getId(), exception);
                return;
            }
        }
    }
}
