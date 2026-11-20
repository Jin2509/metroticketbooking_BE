package com.metroticket_api.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.metroticket_api.metro.announcement.Announcement;
import com.metroticket_api.metro.announcement.AnnouncementRepository;
import com.metroticket_api.metro.announcement.AnnouncementService;
import com.metroticket_api.metro.common.AnnouncementScope;
import com.metroticket_api.metro.common.AnnouncementSeverity;
import com.metroticket_api.metro.common.AnnouncementStatus;
import com.metroticket_api.metro.common.MetroLineStatus;
import com.metroticket_api.metro.common.OperationConfigStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.operation.OperationConfig;
import com.metroticket_api.metro.operation.OperationConfigRepository;
import com.metroticket_api.metro.operation.OperationConfigService;
import com.metroticket_api.metro.station.LineStationRepository;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.metro.train.TrainRepository;
import com.metroticket_api.workflow.WorkflowEngineService;

class ChatbotOperationalDataTest {

    private static final Instant NOW = Instant.parse("2026-11-20T14:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void operationScheduleUsesOnlyActiveConfigurationEffectiveNow() {
        MetroLine line = new MetroLine("BTS-01", "Bến Thành - Suối Tiên", null, null, MetroLineStatus.ACTIVE);
        OperationConfig expired = config(line, 1, OperationConfigStatus.ACTIVE,
                NOW.minusSeconds(3600), NOW);
        OperationConfig current = config(line, 2, OperationConfigStatus.ACTIVE,
                NOW.minusSeconds(60), NOW.plusSeconds(3600));
        OperationConfigRepository configs = mock(OperationConfigRepository.class);
        MetroLineRepository lines = mock(MetroLineRepository.class);
        when(lines.findById(7L)).thenReturn(Optional.of(line));
        when(configs.findByLineIdAndStatus(7L, OperationConfigStatus.ACTIVE))
                .thenReturn(List.of(expired, current));

        OperationConfigService service = new OperationConfigService(configs, lines, mock(TrainRepository.class),
                mock(LineStationRepository.class), mock(WorkflowEngineService.class), clock);

        Optional<com.metroticket_api.metro.operation.OperationConfigDtos.OperationConfigResponse> schedule =
                service.currentActiveForLine(7L);
        assertThat(schedule).isPresent();
        assertThat(schedule.orElseThrow().version()).isEqualTo(2);
        assertThat(schedule.orElseThrow().headwayMinutes()).isEqualTo(8);
    }

    @Test
    void chatbotAnnouncementsExcludeDraftFutureExpiredAndUnrelatedLineNotices() {
        MetroLine requestedLine = mock(MetroLine.class);
        MetroLine otherLine = mock(MetroLine.class);
        when(requestedLine.getId()).thenReturn(7L);
        when(otherLine.getId()).thenReturn(8L);

        Announcement global = notice("Toàn tuyến", AnnouncementStatus.APPROVED,
                AnnouncementScope.ALL, NOW.minusSeconds(60), null, null);
        Announcement matchingLine = notice("Tuyến đang bảo trì", AnnouncementStatus.APPROVED,
                AnnouncementScope.LINE, NOW.minusSeconds(60), null, requestedLine);
        Announcement unrelatedLine = notice("Tuyến khác", AnnouncementStatus.APPROVED,
                AnnouncementScope.LINE, NOW.minusSeconds(60), null, otherLine);
        Announcement draft = notice("Bản nháp", AnnouncementStatus.DRAFT,
                AnnouncementScope.ALL, NOW.minusSeconds(60), null, null);
        Announcement future = notice("Thông báo tương lai", AnnouncementStatus.APPROVED,
                AnnouncementScope.ALL, NOW.plusSeconds(60), null, null);
        Announcement expired = notice("Thông báo hết hạn", AnnouncementStatus.APPROVED,
                AnnouncementScope.ALL, NOW.minusSeconds(3600), NOW, null);
        AnnouncementRepository repository = mock(AnnouncementRepository.class);
        when(repository.findAllByStatusOrderByStartsAtDesc(AnnouncementStatus.APPROVED))
                .thenReturn(List.of(global, matchingLine, unrelatedLine, draft, future, expired));

        AnnouncementService service = new AnnouncementService(repository, mock(MetroLineRepository.class),
                mock(StationRepository.class), mock(WorkflowEngineService.class), clock);

        assertThat(service.listPublishedForChatbot(Set.of(7L), null))
                .extracting(response -> response.title())
                .containsExactly("Toàn tuyến", "Tuyến đang bảo trì");
    }

    private static OperationConfig config(MetroLine line, int version, OperationConfigStatus status,
            Instant effectiveFrom, Instant effectiveTo) {
        return new OperationConfig(line, version, 10, 8, 50, 5,
                LocalTime.of(5, 0), LocalTime.of(23, 0), effectiveFrom, effectiveTo, UUID.randomUUID(), status);
    }

    private static Announcement notice(String title, AnnouncementStatus status, AnnouncementScope scope,
            Instant startsAt, Instant endsAt, MetroLine line) {
        Announcement announcement = new Announcement(title, title, AnnouncementSeverity.INFO,
                startsAt, endsAt, status, scope, null, null);
        announcement.setLine(line);
        return announcement;
    }
}
