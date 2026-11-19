package com.metroticket_api.chatbot;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ChatFaqRepository extends JpaRepository<ChatFaq, Long> {

    @Query(value = """
            SELECT * FROM chatbot_faq
            WHERE status = 'ACTIVE' AND search_vector @@ to_tsquery('simple', :query)
            ORDER BY ts_rank(search_vector, to_tsquery('simple', :query)) DESC, id
            LIMIT 3
            """, nativeQuery = true)
    List<ChatFaq> search(String query);

    @Query("select f from ChatFaq f where f.status = 'ACTIVE' and f.embedding is not null and f.embeddingModel = :model")
    List<ChatFaq> findActiveWithEmbedding(@Param("model") String model);

    @Query("select f from ChatFaq f where f.status = 'ACTIVE' and (f.embedding is null or f.embeddingModel <> :model or f.embeddingModel is null) order by f.id")
    List<ChatFaq> findActiveNeedingEmbedding(@Param("model") String model, Pageable pageable);

    @Modifying
    @Transactional
    @Query("update ChatFaq f set f.embedding = :embedding, f.embeddingModel = :model where f.id = :id and f.status = 'ACTIVE'")
    int updateEmbedding(@Param("id") Long id, @Param("embedding") String embedding, @Param("model") String model);

    @Query("select f from ChatFaq f where f.status = 'ACTIVE' order by f.id desc")
    Page<ChatFaq> findActive(Pageable pageable);

    @Query("select f from ChatFaq f where f.status = 'ACTIVE' and lower(f.question) = lower(:question)")
    Optional<ChatFaq> findActiveByQuestion(String question);
}
