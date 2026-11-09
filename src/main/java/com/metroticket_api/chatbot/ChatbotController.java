package com.metroticket_api.chatbot;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.security.CurrentUser;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Chatbot", description = "UC22: hỏi đáp với chatbot; UC06: kho tri thức")
public class ChatbotController {

    public record AskRequest(Long sessionId, @NotBlank @Size(max = 1000) String content) {
    }

    public record FeedbackRequest(@NotNull ChatMessage.Feedback feedback) {
    }

    public record MessageView(Long id, ChatMessage.Sender sender, String content, ChatMessage.Feedback feedback, Instant createdAt) {
        static MessageView of(ChatMessage m) {
            return new MessageView(m.getId(), m.getSender(), m.getContent(), m.getFeedback(), m.getCreatedAt());
        }
    }

    public record FaqRequest(@NotBlank @Size(max = 30) String category, @NotBlank @Size(max = 1000) String question,
            @NotBlank @Size(max = 5000) String answer, @Size(max = 500) String keywords) {
    }

    public record FaqView(Long id, String category, String question, String answer, String keywords, Instant updatedAt) {
        static FaqView of(ChatFaq f) {
            return new FaqView(f.getId(), f.getCategory(), f.getQuestion(), f.getAnswer(), f.getKeywords(), f.getUpdatedAt());
        }
    }

    private final ChatbotService chatbot;
    private final ChatFaqService faqs;

    public ChatbotController(ChatbotService chatbot, ChatFaqService faqs) {
        this.chatbot = chatbot;
        this.faqs = faqs;
    }

    @PreAuthorize("hasAuthority('CHATBOT_USE')")
    @PostMapping("/chatbot/messages")
    public ChatbotService.Answer ask(@Valid @RequestBody AskRequest request) {
        return chatbot.ask(CurrentUser.id(), request.sessionId(), request.content());
    }

    @PreAuthorize("hasAuthority('CHATBOT_USE')")
    @GetMapping("/chatbot/sessions/{sessionId}/messages")
    public List<MessageView> history(@PathVariable Long sessionId) {
        return chatbot.history(CurrentUser.id(), sessionId).stream().map(MessageView::of).toList();
    }

    @PreAuthorize("hasAuthority('CHATBOT_USE')")
    @PostMapping("/chatbot/messages/{messageId}/feedback")
    public MessageView feedback(@PathVariable Long messageId, @Valid @RequestBody FeedbackRequest request) {
        return MessageView.of(chatbot.rate(CurrentUser.id(), messageId, request.feedback()));
    }

    @PreAuthorize("hasAuthority('CHATBOT_MANAGE')")
    @GetMapping("/admin/chatbot/faqs")
    public Page<FaqView> faqs(@PageableDefault(size = 20) Pageable pageable) {
        return faqs.list(pageable).map(FaqView::of);
    }

    @PreAuthorize("hasAuthority('CHATBOT_MANAGE')")
    @PostMapping("/admin/chatbot/faqs")
    @ResponseStatus(HttpStatus.CREATED)
    public FaqView createFaq(@Valid @RequestBody FaqRequest request) {
        return FaqView.of(faqs.create(request));
    }

    @PreAuthorize("hasAuthority('CHATBOT_MANAGE')")
    @PutMapping("/admin/chatbot/faqs/{id}")
    public FaqView updateFaq(@PathVariable Long id, @Valid @RequestBody FaqRequest request) {
        return FaqView.of(faqs.update(id, request));
    }

    @PreAuthorize("hasAuthority('CHATBOT_MANAGE')")
    @DeleteMapping("/admin/chatbot/faqs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFaq(@PathVariable Long id) {
        faqs.delete(id);
    }
}
