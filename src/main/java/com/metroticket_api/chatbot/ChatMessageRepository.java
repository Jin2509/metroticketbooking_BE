package com.metroticket_api.chatbot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderByIdAsc(Long sessionId);

    List<ChatMessage> findBySessionIdOrderByIdDesc(Long sessionId, Pageable pageable);

    @Query("""
            select count(m) from ChatMessage m, ChatSession s
            where s.id = m.sessionId and s.userId = :userId
              and m.sender = com.metroticket_api.chatbot.ChatMessage.Sender.USER and m.createdAt >= :since
            """)
    long countUserMessagesSince(UUID userId, Instant since);

    /** Tin nhắn của bot thuộc phiên của chính người dùng: người khác không đánh giá được. */
    @Query("""
            select m from ChatMessage m, ChatSession s
            where s.id = m.sessionId and s.userId = :userId and m.id = :id
              and m.sender = com.metroticket_api.chatbot.ChatMessage.Sender.BOT
            """)
    Optional<ChatMessage> findOwnBotMessage(Long id, UUID userId);
}
