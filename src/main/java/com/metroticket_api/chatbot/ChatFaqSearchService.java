package com.metroticket_api.chatbot;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metroticket_api.chatbot.GeminiEmbeddingClient.EmbeddingException;

/**
 * Tìm FAQ bằng hybrid retrieval: full-text PostgreSQL làm nguồn cơ bản,
 * sau đó gộp kết quả semantic bằng Reciprocal Rank Fusion.
 * Lỗi embedding hoặc model chưa cấu hình sẽ fallback về full-text.
 */
@Service
public class ChatFaqSearchService {

    private static final Logger log = LoggerFactory.getLogger(ChatFaqSearchService.class);
    private static final int MAX_QUERY_TERMS = 12;
    private static final int MAX_RESULTS = 3;
    private static final int RRF_K = 60;
    private static final double MIN_COSINE_SIMILARITY = 0.30;
    private static final double MAX_COSINE_DISTANCE = 1 - MIN_COSINE_SIMILARITY;

    private final ChatFaqRepository faqs;
    private final GeminiEmbeddingClient embeddings;
    private final MeterRegistry meters;

    public ChatFaqSearchService(ChatFaqRepository faqs, GeminiEmbeddingClient embeddings, MeterRegistry meters) {
        this.faqs = faqs;
        this.embeddings = embeddings;
        this.meters = meters;
    }

    public List<ChatFaq> search(String question) {
        String query = toTsQuery(question);
        List<ChatFaq> lexical = query.isEmpty() ? List.of() : faqs.search(query);
        if (!embeddings.available()) {
            meters.counter("chatbot.faq.retrieval", "mode", "full_text").increment();
            return lexical;
        }
        try {
            List<Double> queryVector = embeddings.embed(question);
            List<ChatFaq> semantic = faqs.searchSemantic(
                    GeminiEmbeddingClient.serializeVector(queryVector), embeddings.model(), MAX_COSINE_DISTANCE);
            meters.counter("chatbot.faq.retrieval", "mode", "hybrid").increment();
            return reciprocalRankFusion(lexical, semantic);
        } catch (EmbeddingException exception) {
            log.warn("Semantic FAQ retrieval unavailable; falling back to PostgreSQL full-text search", exception);
            meters.counter("chatbot.faq.retrieval", "mode", "fallback").increment();
            return lexical;
        }
    }

    private List<ChatFaq> reciprocalRankFusion(List<ChatFaq> lexical, List<ChatFaq> semantic) {
        Map<String, RankedFaq> ranked = new LinkedHashMap<>();
        addRank(ranked, lexical);
        addRank(ranked, semantic);
        return ranked.values().stream()
                .sorted(Comparator.comparingDouble(RankedFaq::score).reversed())
                .limit(MAX_RESULTS)
                .map(RankedFaq::faq)
                .toList();
    }

    private static void addRank(Map<String, RankedFaq> ranked, List<ChatFaq> results) {
        for (int i = 0; i < results.size(); i++) {
            ChatFaq faq = results.get(i);
            double rankScore = 1.0 / (RRF_K + i + 1);
            ranked.compute(faq.getQuestion(), (question, existing) -> existing == null
                    ? new RankedFaq(faq, rankScore)
                    : new RankedFaq(existing.faq(), existing.score() + rankScore));
        }
    }

    static String toTsQuery(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> word.length() >= 2)
                .distinct()
                .limit(MAX_QUERY_TERMS)
                .collect(Collectors.joining(" | "));
    }

    private record RankedFaq(ChatFaq faq, double score) {
    }
}
