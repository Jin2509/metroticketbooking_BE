package com.metroticket_api.chatbot;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "chatbot_message")
public class ChatMessage {

    public enum Sender { USER, BOT }

    public enum Feedback { LIKE, DISLIKE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Sender sender;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "action_called")
    private String actionCalled;

    @Column(name = "response_time_ms")
    private Integer responseTimeMs;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Feedback feedback;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChatMessage() {
    }

    ChatMessage(Long sessionId, Sender sender, String content, String actionCalled, Integer responseTimeMs, Instant createdAt) {
        this.sessionId = sessionId;
        this.sender = sender;
        this.content = content;
        this.actionCalled = actionCalled;
        this.responseTimeMs = responseTimeMs;
        this.createdAt = createdAt;
    }

    void rate(Feedback value) {
        feedback = value;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Sender getSender() { return sender; }
    public String getContent() { return content; }
    public String getActionCalled() { return actionCalled; }
    public Feedback getFeedback() { return feedback; }
    public Instant getCreatedAt() { return createdAt; }
}
