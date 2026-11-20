package com.metroticket_api.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.metroticket_api.chatbot.ChatMessage.Sender;
import com.metroticket_api.external.facility.GeoapifyGeocodingClient.PlaceCandidate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

class ChatbotPlaceConfirmationTest {

    private static final Instant NOW = Instant.parse("2026-11-20T14:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void mapsNumericConfirmationFromSessionStateWithoutCallingGeminiOrGeocoder() throws Exception {
        UUID userId = UUID.randomUUID();
        ChatSession session = new ChatSession(userId, NOW);
        PlaceCandidate selected = new PlaceCandidate(
                "Landmark 81", "Landmark 81, Bình Thạnh, TP. Hồ Chí Minh",
                new BigDecimal("10.795"), new BigDecimal("106.722"), 0.95);
        session.setPendingPlaceResolution(json.writeValueAsString(
                new PendingPlaceResolution(List.of(selected), "Tìm ga gần Landmark 81", NOW.plusSeconds(300))),
                NOW.plusSeconds(300));

        ChatSessionRepository sessions = mock(ChatSessionRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ChatFaqSearchService faqSearch = mock(ChatFaqSearchService.class);
        GeminiChatClient gemini = mock(GeminiChatClient.class);
        ChatbotTools tools = mock(ChatbotTools.class);
        when(sessions.findByIdAndUserId(12L, userId)).thenReturn(Optional.of(session));
        when(messages.findBySessionIdOrderByIdDesc(any(), any())).thenReturn(List.of(
                new ChatMessage(12L, Sender.BOT, "1. Landmark 81, Bình Thạnh", null, null, NOW.minusSeconds(5))));
        when(messages.save(any(ChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessions.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tools.resolveSelectedPlace(selected)).thenReturn(Map.of(
                "status", "RESOLVED",
                "nearestStations", List.of(Map.of("name", "Tân Cảng", "distanceMeters", 650))));

        ChatbotService chatbot = new ChatbotService(sessions, messages, faqSearch, gemini, tools, clock,
                json, new SimpleMeterRegistry());
        ChatbotService.Answer answer = chatbot.ask(userId, 12L, "số 1");

        assertThat(answer.answer())
                .contains("Đã xác nhận địa điểm Landmark 81")
                .contains("Tân Cảng")
                .contains("650 m đường chim bay");
        assertThat(session.getPendingPlaceResolution()).isNull();
        verify(tools).resolveSelectedPlace(selected);
        verify(gemini, never()).generate(any(), any(), any());
        verify(faqSearch, never()).search(any());
    }

    @Test
    void expiredPlaceConfirmationIsDiscardedInsteadOfApplied() throws Exception {
        UUID userId = UUID.randomUUID();
        ChatSession session = new ChatSession(userId, NOW);
        PlaceCandidate selected = new PlaceCandidate(
                "Landmark 81", "Landmark 81, Bình Thạnh, TP. Hồ Chí Minh",
                new BigDecimal("10.795"), new BigDecimal("106.722"), 0.95);
        session.setPendingPlaceResolution(json.writeValueAsString(
                new PendingPlaceResolution(List.of(selected), "Tìm ga gần Landmark 81", NOW.minusSeconds(1))),
                NOW.minusSeconds(1));

        ChatSessionRepository sessions = mock(ChatSessionRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ChatFaqSearchService faqSearch = mock(ChatFaqSearchService.class);
        GeminiChatClient gemini = mock(GeminiChatClient.class);
        ChatbotTools tools = mock(ChatbotTools.class);
        when(sessions.findByIdAndUserId(12L, userId)).thenReturn(Optional.of(session));
        when(messages.findBySessionIdOrderByIdDesc(any(), any())).thenReturn(List.of());
        when(messages.save(any(ChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessions.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gemini.available()).thenReturn(false);
        when(faqSearch.search("số 1")).thenReturn(List.of());

        ChatbotService chatbot = new ChatbotService(sessions, messages, faqSearch, gemini, tools, clock,
                json, new SimpleMeterRegistry());
        ChatbotService.Answer answer = chatbot.ask(userId, 12L, "số 1");

        assertThat(answer.answer()).contains("đã hết hạn");
        assertThat(session.getPendingPlaceResolution()).isNull();
        verify(tools, never()).resolveSelectedPlace(any());
    }
}
