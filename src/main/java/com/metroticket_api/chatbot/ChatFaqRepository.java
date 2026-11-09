package com.metroticket_api.chatbot;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChatFaqRepository extends JpaRepository<ChatFaq, Long> {

    @Query(value = """
            SELECT * FROM chatbot_faq
            WHERE status = 'ACTIVE' AND search_vector @@ to_tsquery('simple', :query)
            ORDER BY ts_rank(search_vector, to_tsquery('simple', :query)) DESC, id
            LIMIT 3
            """, nativeQuery = true)
    List<ChatFaq> search(String query);

    @Query("select f from ChatFaq f where f.status = 'ACTIVE' order by f.id desc")
    Page<ChatFaq> findActive(Pageable pageable);

    @Query("select f from ChatFaq f where f.status = 'ACTIVE' and lower(f.question) = lower(:question)")
    Optional<ChatFaq> findActiveByQuestion(String question);
}
