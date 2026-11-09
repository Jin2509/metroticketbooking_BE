package com.metroticket_api.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.metroticket_api.metro.common.StationStatus;
import com.metroticket_api.metro.staff.StaffStationService;
import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.ticket.TicketDtos.GateValidationRequest;
import com.metroticket_api.ticket.TicketDtos.GateValidationResponse;

class GateServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T02:00:00Z");

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TicketValidationRepository validations = mock(TicketValidationRepository.class);
    private final StationRepository stations = mock(StationRepository.class);
    private GateService gate;

    private Station benThanh;
    private Station thuDuc;

    @BeforeEach
    void setUp() {
        gate = new GateService(tickets, validations, stations, mock(StaffStationService.class), null, mock(FareService.class), mock(com.metroticket_api.payment.PaymentService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        benThanh = station(1L, "Bến Thành", StationStatus.ACTIVE);
        thuDuc = station(11L, "Thủ Đức", StationStatus.ACTIVE);
    }

    private Station station(long id, String name, StationStatus status) {
        Station s = mock(Station.class);
        when(s.getId()).thenReturn(id);
        when(s.getNameStation()).thenReturn(name);
        when(s.getCode()).thenReturn("ST" + id);
        when(s.getStatus()).thenReturn(status);
        when(stations.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private Ticket pass(DurationUnit unit, int value, Instant activationDeadline) {
        TicketProduct product = new TicketProduct("P", "P", null, unit, value, 40_000, 1, 30, ProductStatus.ACTIVE, null);
        Ticket t = new Ticket(UUID.randomUUID(), product, 1, NOW.minusSeconds(600), activationDeadline);
        when(tickets.findByQrTokenForUpdate(t.getQrToken())).thenReturn(Optional.of(t));
        return t;
    }

    private Ticket dayPass() {
        return pass(DurationUnit.DAY, 1, NOW.plusSeconds(30L * 86400));
    }

    private GateValidationResponse tap(Ticket t, Station at, GateDirection direction) {
        return gate.validate(new GateValidationRequest(t.getQrToken(), at.getId(), direction));
    }

    @Test
    void firstEntryActivatesAndPassCanEnterAndExitAnywhere() {
        Ticket t = dayPass();
        GateValidationResponse first = tap(t, benThanh, GateDirection.ENTRY);
        assertThat(first.result()).isEqualTo(GateResult.ACCEPTED);
        assertThat(first.reason()).contains("Kích hoạt");
        assertThat(t.getActivatedAt()).isEqualTo(NOW);
        assertThat(t.getValidUntil()).isEqualTo(NOW.plusSeconds(86400));

        assertThat(tap(t, thuDuc, GateDirection.EXIT).result()).isEqualTo(GateResult.ACCEPTED);
        assertThat(t.getStatus()).isEqualTo(TicketStatus.ACTIVE);
        assertThat(tap(t, thuDuc, GateDirection.ENTRY).result()).as("dùng tiếp cả ngày").isEqualTo(GateResult.ACCEPTED);
    }

    @Test
    void antiPassbackBlocksSecondEntry() {
        Ticket t = dayPass();
        tap(t, benThanh, GateDirection.ENTRY);
        GateValidationResponse second = tap(t, thuDuc, GateDirection.ENTRY);
        assertThat(second.result()).isEqualTo(GateResult.REJECTED);
        assertThat(second.reason()).contains("chưa quẹt ra");
    }

    @Test
    void closedStationRejectsEntry() {
        Station closed = station(5L, "Tân Cảng", StationStatus.MAINTENANCE);
        assertThat(tap(dayPass(), closed, GateDirection.ENTRY).result()).isEqualTo(GateResult.REJECTED);
    }

    @Test
    void exitWithoutEntryIsRejected() {
        assertThat(tap(dayPass(), benThanh, GateDirection.EXIT).result()).isEqualTo(GateResult.REJECTED);
    }

    @Test
    void notActivatedBeforeDeadlineIsExpired() {
        Ticket t = pass(DurationUnit.DAY, 1, NOW.minusSeconds(1));
        GateValidationResponse r = tap(t, benThanh, GateDirection.ENTRY);
        assertThat(r.result()).isEqualTo(GateResult.REJECTED);
        assertThat(r.reason()).contains("quá hạn kích hoạt");
        assertThat(t.getStatus()).isEqualTo(TicketStatus.EXPIRED);
    }

    @Test
    void unpaidTicketIsRejected() {
        Ticket t = dayPass();
        t.awaitPayment();
        assertThat(tap(t, benThanh, GateDirection.ENTRY).reason()).contains("chưa được thanh toán");
    }

    @Test
    void unknownQrIsLoggedAsRejected() {
        when(tickets.findByQrTokenForUpdate("bogus")).thenReturn(Optional.empty());
        GateValidationResponse r = gate.validate(new GateValidationRequest("bogus", 1L, GateDirection.ENTRY));
        assertThat(r.result()).isEqualTo(GateResult.REJECTED);
        ArgumentCaptor<TicketValidation> saved = ArgumentCaptor.forClass(TicketValidation.class);
        verify(validations).save(saved.capture());
        assertThat(saved.getValue().getTicket()).isNull();
    }
}
