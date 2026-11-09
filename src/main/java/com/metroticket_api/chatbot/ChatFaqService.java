package com.metroticket_api.chatbot;

import java.time.Clock;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.audit.AuditLogService;
import com.metroticket_api.chatbot.ChatbotController.FaqRequest;
import com.metroticket_api.security.CurrentUser;

/** Kho tri thức chatbot (UC06). Nội dung mới có hiệu lực ngay cho câu hỏi sau đó. */
@Service
public class ChatFaqService {

    private final ChatFaqRepository faqs;
    private final AuditLogService audit;
    private final Clock clock;

    public ChatFaqService(ChatFaqRepository faqs, AuditLogService audit, Clock clock) {
        this.faqs = faqs;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<ChatFaq> list(Pageable pageable) {
        return faqs.findActive(pageable);
    }

    @Transactional
    public ChatFaq create(FaqRequest r) {
        ensureUnique(r.question().trim(), null);
        ChatFaq faq = faqs.save(new ChatFaq(r.category().trim(), r.question().trim(), r.answer().trim(), blankToNull(r.keywords()),
                CurrentUser.id(), clock.instant()));
        audit.log("CHATBOT_FAQ_CREATE", "chatbot_faq", faq.getId(), faq.getQuestion());
        return faq;
    }

    @Transactional
    public ChatFaq update(Long id, FaqRequest r) {
        ChatFaq faq = require(id);
        ensureUnique(r.question().trim(), id);
        String old = faq.getQuestion() + " => " + faq.getAnswer();
        faq.update(r.category().trim(), r.question().trim(), r.answer().trim(), blankToNull(r.keywords()), clock.instant());
        audit.log("CHATBOT_FAQ_UPDATE", "chatbot_faq", id, null, old, faq.getQuestion() + " => " + faq.getAnswer());
        return faq;
    }

    @Transactional
    public void delete(Long id) {
        ChatFaq faq = require(id);
        faq.markDeleted(clock.instant());
        audit.log("CHATBOT_FAQ_DELETE", "chatbot_faq", id, faq.getQuestion());
    }

    private void ensureUnique(String question, Long selfId) {
        faqs.findActiveByQuestion(question).filter(f -> !f.getId().equals(selfId)).ifPresent(f -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Câu hỏi đã tồn tại");
        });
    }

    private ChatFaq require(Long id) {
        return faqs.findById(id).filter(f -> "ACTIVE".equals(f.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy câu hỏi"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
