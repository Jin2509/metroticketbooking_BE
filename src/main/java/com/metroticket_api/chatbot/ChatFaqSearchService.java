package com.metroticket_api.chatbot;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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

    private final ChatFaqRepository faqs;
    private final GeminiEmbeddingClient embeddings;

    public ChatFaqSearchService(ChatFaqRepository faqs, GeminiEmbeddingClient embeddings) {
        this.faqs = faqs;
        this.embeddings = embeddings;
    }

    public List<ChatFaq> search(String question) {
        String query = toTsQuery(question);
        List<ChatFaq> lexical = query.isEmpty() ? List.of() : faqs.search(query);
        if (!embeddings.available()) {
            return lexical;
        }
        try {
            List<Double> queryVector = embeddings.embed(question);
            List<ChatFaq> semantic = semanticResults(queryVector);
            return reciprocalRankFusion(lexical, semantic);
        } catch (EmbeddingException exception) {
            log.warn("Semantic FAQ retrieval unavailable; falling back to PostgreSQL full-text search", exception);
            return lexical;
        }
    }

    private List<ChatFaq> semanticResults(List<Double> queryVector) {
        return faqs.findActiveWithEmbedding(embeddings.model()).stream()
                .map(faq -> new Similarity(faq, cosineSimilarity(queryVector, faq.getEmbedding())))
                .filter(result -> result.score() >= MIN_COSINE_SIMILARITY)
                .sorted(Comparator.comparingDouble(Similarity::score).reversed())
                .limit(MAX_RESULTS)
                .map(Similarity::faq)
                .toList();
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

    private static double cosineSimilarity(List<Double> query, String stored) {
        Optional<List<Double>> parsed = parseVector(stored);
        if (parsed.isEmpty() || query.size() != parsed.get().size()) {
            return -1;
        }
        double dot = 0;
        double queryNorm = 0;
        double storedNorm = 0;
        for (int i = 0; i < query.size(); i++) {
            double q = query.get(i);
            double value = parsed.get().get(i);
            dot += q * value;
            queryNorm += q * q;
            storedNorm += value * value;
        }
        if (queryNorm == 0 || storedNorm == 0) {
            return -1;
        }
        return dot / (Math.sqrt(queryNorm) * Math.sqrt(storedNorm));
    }

    private static Optional<List<Double>> parseVector(String stored) {
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Arrays.stream(stored.split(",")).map(Double::parseDouble).toList());
        } catch (NumberFormatException exception) {
            log.warn("Ignoring malformed stored FAQ embedding");
            return Optional.empty();
        }
    }

    static String toTsQuery(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> word.length() >= 2)
                .distinct()
                .limit(MAX_QUERY_TERMS)
                .collect(Collectors.joining(" | "));
    }

    private record Similarity(ChatFaq faq, double score) {
    }

    private record RankedFaq(ChatFaq faq, double score) {
    }
}
