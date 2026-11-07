package com.metroticket_api.metro.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.MetroLineStatus;
import com.metroticket_api.metro.common.OperationConfigStatus;
import com.metroticket_api.metro.common.TrainStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.operation.OperationConfigDtos.UpdateOperationConfigRequest;
import com.metroticket_api.metro.station.LineStation;
import com.metroticket_api.metro.station.LineStationRepository;
import com.metroticket_api.metro.train.Train;
import com.metroticket_api.metro.train.TrainRepository;
import com.metroticket_api.workflow.WorkflowEngineService;

/** Dùng WorkflowEngineService thật để kiểm tra luồng maker-checker. */
class OperationConfigWorkflowTest {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID MANAGER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Instant NOW = Instant.parse("2026-11-07T03:00:00Z");

    private final OperationConfigRepository configs = mock(OperationConfigRepository.class);
    private final MetroLineRepository lines = mock(MetroLineRepository.class);
    private final TrainRepository trains = mock(TrainRepository.class);
    private final LineStationRepository lineStations = mock(LineStationRepository.class);
    private OperationConfigService service;
    private MetroLine line;

    @BeforeEach
    void setUp() {
        line = mock(MetroLine.class);
        when(line.getId()).thenReturn(1L);
        when(line.getStatus()).thenReturn(MetroLineStatus.ACTIVE);
        when(lines.findById(1L)).thenReturn(Optional.of(line));
        when(configs.save(any(OperationConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(configs.saveAndFlush(any(OperationConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        List<LineStation> route = new ArrayList<>();
        for (int travel : new int[] { 0, 42, 64, 114, 57, 75, 61, 103, 94, 90, 112, 147, 101, 130 }) {
            LineStation ls = mock(LineStation.class);
            when(ls.getTravelTimeFromPrevSeconds()).thenReturn(travel);
            when(ls.getDwellTimeSeconds()).thenReturn(30);
            route.add(ls);
        }
        when(lineStations.findByLineIdOrderByStationOrderAsc(1L)).thenReturn(route);
        List<Train> fleet = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Train t = mock(Train.class);
            when(t.getStatus()).thenReturn(TrainStatus.READY);
            fleet.add(t);
        }
        when(trains.findByLineId(1L)).thenReturn(fleet);
        service = new OperationConfigService(configs, lines, trains, lineStations, new WorkflowEngineService(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(UUID id, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id.toString(),
                null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private OperationConfig config(long id, int version, OperationConfigStatus status, UUID createdBy) {
        OperationConfig c = new OperationConfig(line, version, 5, 14, 30, 5, LocalTime.of(5, 0), LocalTime.of(22, 0),
                Instant.parse("2024-12-21T17:00:00Z"), null, createdBy, status);
        OperationConfig spy = org.mockito.Mockito.spy(c);
        when(spy.getId()).thenReturn(id);
        when(configs.findById(id)).thenReturn(Optional.of(spy));
        return spy;
    }

    @Test
    void creatorCannotApproveOwnConfig() {
        config(10, 2, OperationConfigStatus.PENDING_APPROVAL, ADMIN);
        loginAs(ADMIN, "ADMIN");
        assertThatThrownBy(() -> service.approve(10L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void managerApprovesAndApproverIsTakenFromToken() {
        OperationConfig c = config(10, 2, OperationConfigStatus.PENDING_APPROVAL, ADMIN);
        loginAs(MANAGER, "MANAGER");
        service.approve(10L);
        assertThat(c.getStatus()).isEqualTo(OperationConfigStatus.APPROVED);
        assertThat(c.getApprovedBy()).isEqualTo(MANAGER);
        assertThat(c.getApprovedAt()).isEqualTo(NOW);
    }

    @Test
    void wrongTransitionIsConflictNotServerError() {
        config(10, 2, OperationConfigStatus.DRAFT, ADMIN);
        loginAs(MANAGER, "MANAGER");
        assertThatThrownBy(() -> service.approve(10L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void editingRejectedConfigReturnsItToDraft() {
        OperationConfig c = config(10, 2, OperationConfigStatus.REJECTED, ADMIN);
        c.setRejectionReason("headway quá dày");
        loginAs(ADMIN, "ADMIN");
        service.update(10L, new UpdateOperationConfigRequest(null, null, null, null, LocalTime.of(5, 30), null, null, null, null));
        assertThat(c.getStatus()).isEqualTo(OperationConfigStatus.DRAFT);
        assertThat(c.getRejectionReason()).isNull();
        assertThat(c.getStartTime()).isEqualTo(LocalTime.of(5, 30));
    }

    @Test
    void editingApprovedConfigCreatesNewDraftWithMaxVersionPlusOne() {
        OperationConfig approved = config(10, 2, OperationConfigStatus.APPROVED, ADMIN);
        OperationConfig latest = config(11, 7, OperationConfigStatus.DRAFT, ADMIN);
        when(configs.findTopByLineIdOrderByVersionDesc(1L)).thenReturn(Optional.of(latest));
        loginAs(ADMIN, "ADMIN");
        OperationConfigDtos.OperationConfigResponse draft = service.update(10L,
                new UpdateOperationConfigRequest(null, null, null, null, null, LocalTime.of(23, 0), null, null, null));
        assertThat(draft.version()).isEqualTo(8);
        assertThat(draft.status()).isEqualTo(OperationConfigStatus.DRAFT);
        assertThat(approved.getEndTime()).as("bản đã duyệt không bị sửa").isEqualTo(LocalTime.of(22, 0));
    }

    @Test
    void activatingReplacesPreviousActiveConfig() {
        OperationConfig previous = config(9, 1, OperationConfigStatus.ACTIVE, ADMIN);
        OperationConfig next = config(10, 2, OperationConfigStatus.APPROVED, ADMIN);
        when(configs.findByLineIdAndStatus(1L, OperationConfigStatus.ACTIVE)).thenReturn(List.of(previous));
        loginAs(MANAGER, "MANAGER");
        service.activate(10L);
        assertThat(previous.getStatus()).isEqualTo(OperationConfigStatus.INACTIVE);
        assertThat(next.getStatus()).isEqualTo(OperationConfigStatus.ACTIVE);
        verify(configs).saveAndFlush(previous);
    }

    @Test
    void cannotActivateBeforeEffectiveDate() {
        OperationConfig future = config(10, 2, OperationConfigStatus.APPROVED, ADMIN);
        future.setEffectiveFrom(NOW.plusSeconds(86400));
        loginAs(MANAGER, "MANAGER");
        assertThatThrownBy(() -> service.activate(10L)).hasMessageContaining("chưa đến ngày hiệu lực");
    }
}
