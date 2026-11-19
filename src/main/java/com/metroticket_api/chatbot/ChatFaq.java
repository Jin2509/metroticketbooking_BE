package com.metroticket_api.chatbot;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Một mục trong kho tri thức chatbot. Xoá là đánh dấu DELETED.
 * search_vector do PostgreSQL tự sinh; embedding và embeddingModel bổ sung
 * semantic search và bảo vệ vector khỏi model embedding không tương thích.
 */
@Entity
@Table(name = "chatbot_faq")
public class ChatFaq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(columnDefinition = "TEXT")
    private String keywords;

    @Column(columnDefinition = "TEXT")
    private String embedding;

    @Column(name = "embedding_model", length = 100)
    private String embeddingModel;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChatFaq() {
    }

    ChatFaq(String category, String question, String answer, String keywords, UUID createdBy, Instant now) {
        this.category = category;
        this.question = question;
        this.answer = answer;
        this.keywords = keywords;
        this.createdBy = createdBy;
        this.status = "ACTIVE";
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(String category, String question, String answer, String keywords, Instant now) {
        this.category = category;
        this.question = question;
        this.answer = answer;
        this.keywords = keywords;
        this.embedding = null;
        this.embeddingModel = null;
        this.updatedAt = now;
    }

    String embeddingText() {
        return "Danh mục: " + category + "\nCâu hỏi: " + question + "\nTrả lời: " + answer
                + (keywords == null || keywords.isBlank() ? "" : "\nTừ khóa: " + keywords);
    }

    void setEmbedding(String embedding, String embeddingModel) {
        this.embedding = embedding;
        this.embeddingModel = embeddingModel;
    }

    void markDeleted(Instant now) {
        this.status = "DELETED";
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getCategory() { return category; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public String getKeywords() { return keywords; }
    public String getEmbedding() { return embedding; }
    public String getEmbeddingModel() { return embeddingModel; }
    public String getStatus() { return status; }
    public Instant getUpdatedAt() { return updatedAt; }
}
