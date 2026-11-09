package com.metroticket_api.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Pageable;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.metroticket_api.chatbot.ChatFaqRepository;
import com.metroticket_api.chatbot.ChatMessage;
import com.metroticket_api.chatbot.ChatMessageRepository;
import com.metroticket_api.chatbot.ChatSession;
import com.metroticket_api.chatbot.ChatSessionRepository;
import com.metroticket_api.chatbot.ChatbotService;
import com.metroticket_api.chatbot.ChatbotTools;
import com.metroticket_api.chatbot.GeminiChatClient;
import com.metroticket_api.report.ReportExporter;
import com.metroticket_api.report.ReportService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SpecCompletionUnitTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final ChatFaqRepository faqs = mock(ChatFaqRepository.class);
    private final GeminiChatClient gemini = mock(GeminiChatClient.class);
    private final ChatbotTools tools = mock(ChatbotTools.class);
    private final ChatbotService chatbot = new ChatbotService(sessions, messages, faqs, gemini, tools, Clock.systemUTC());
    private final UUID user = UUID.randomUUID();

    private void stubRepositories() {
        when(sessions.save(any(ChatSession.class))).thenAnswer(i -> i.getArgument(0));
        when(messages.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        when(messages.findBySessionIdOrderByIdDesc(any(), any(Pageable.class))).thenReturn(List.of());
        when(faqs.search(any())).thenReturn(List.of());
        when(gemini.available()).thenReturn(true);
        when(tools.declarations()).thenReturn(List.of());
    }

    private static JsonNode content(String json) {
        return JSON.readTree(json);
    }

    @Test
    void functionCallUsesLoggedInUserAndFeedsResultBackToModel() throws Exception {
        stubRepositories();
        when(tools.execute(eq("get_my_points"), any(), eq(user))).thenReturn(Map.of("result", 120));
        when(gemini.generate(any(), any(), any()))
                .thenReturn(content("{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"get_my_points\",\"args\":{\"user_id\":\"nguoi-khac\"}}}]}"))
                .thenReturn(content("{\"role\":\"model\",\"parts\":[{\"text\":\"Bạn có 120 điểm.\"}]}"));

        ChatbotService.Answer answer = chatbot.ask(user, null, "Tôi có bao nhiêu điểm?");

        assertThat(answer.answer()).isEqualTo("Bạn có 120 điểm.");
        assertThat(answer.actionCalled()).isEqualTo("get_my_points");
        verify(tools).execute(eq("get_my_points"), any(), eq(user));
    }

    @Test
    void endlessFunctionCallsStopAtTheRoundLimit() throws Exception {
        stubRepositories();
        when(tools.execute(any(), any(), any())).thenReturn(Map.of("result", 1));
        when(gemini.generate(any(), any(), any()))
                .thenReturn(content("{\"parts\":[{\"functionCall\":{\"name\":\"get_my_points\",\"args\":{}}}]}"));

        assertThat(chatbot.ask(user, null, "lặp").answer()).contains("trợ lý đang gặp sự cố");
        org.mockito.Mockito.verify(gemini, org.mockito.Mockito.times(4)).generate(any(), any(), any());
    }

    @Test
    void aiFailureGivesFriendlyAnswer() throws Exception {
        stubRepositories();
        when(gemini.generate(any(), any(), any())).thenThrow(new GeminiChatClient.ChatException("lỗi", null));
        assertThat(chatbot.ask(user, null, "xin chào").answer()).contains("trợ lý đang gặp sự cố");
    }

    @Test
    void cannotContinueSomeoneElsesSession() {
        when(sessions.findByIdAndUserId(5L, user)).thenReturn(Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> chatbot.ask(user, 5L, "x"))
                .hasMessageContaining("Không tìm thấy phiên");
    }

    private static final ReportService.Report REPORT = new ReportService.Report("Doanh thu theo ngày", LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 2), 3, 90000L, List.of(new ReportService.Row("Vé một ngày", 3, 90000L)));

    @Test
    void excelFileMatchesReport() throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(new java.io.ByteArrayInputStream(new ReportExporter().excel(REPORT)))) {
            assertThat(book.getSheetAt(0).getRow(2).getCell(0).getStringCellValue()).isEqualTo("Vé một ngày");
            assertThat(book.getSheetAt(0).getRow(3).getCell(2).getNumericCellValue()).isEqualTo(90000d);
        }
    }

    @Test
    void pdfRendersVietnamese() throws Exception {
        byte[] pdf = new ReportExporter().pdf(REPORT);
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(new PdfTextExtractor(reader).getTextFromPage(1)).contains("Vé một ngày");
        }
    }
}
