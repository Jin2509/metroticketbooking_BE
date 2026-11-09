package com.metroticket_api.ticket;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.green.GreenJourneyService;
import com.metroticket_api.loyalty.CheckInAccepted;
import com.metroticket_api.metro.common.StationStatus;
import com.metroticket_api.metro.staff.StaffStationService;
import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.payment.PaymentPurpose;
import com.metroticket_api.payment.PaymentService;
import com.metroticket_api.rbac.PermissionCodes;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.ticket.TicketDtos.GateValidationRequest;
import com.metroticket_api.ticket.TicketDtos.GateValidationResponse;
import com.metroticket_api.ticket.TicketDtos.StationRef;

/**
 * Soát vé tại cổng (nhân viên quét QR trên app hành khách). Vé theo thời gian vào/ra ở ga nào cũng được; vé lượt phải
 * vào đúng ga đi, dùng một lần, ra quá ga đã mua thì thu bù chênh lệch, đi ít hơn không hoàn tiền.
 *
 * <p><b>Quẹt vào</b>: vé ACTIVE, còn hạn (chưa kích hoạt: còn hạn kích hoạt), ga đang ACTIVE. Vé đang IN_USE bị chặn
 * (chống dùng 1 vé cho 2 người – anti-passback). Lần quẹt vào đầu tiên kích hoạt vé.</p>
 * <p><b>Quẹt ra</b>: vé phải IN_USE; đã ở trong ga thì luôn được ra kể cả vé vừa hết hạn.</p>
 * <p>Mọi lượt quẹt (kể cả bị từ chối) được ghi vào ticket_validation; từ chối trả HTTP 200 với result = REJECTED.</p>
 */
@Service
public class GateService {

    private final TicketRepository tickets;
    private final TicketValidationRepository validations;
    private final StationRepository stations;
    private final StaffStationService staffStations;
    private final GreenJourneyService greenJourney;
    private final FareService fares;
    private final PaymentService payments;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher events;

    public GateService(TicketRepository tickets, TicketValidationRepository validations, StationRepository stations,
            StaffStationService staffStations, GreenJourneyService greenJourney, FareService fares,
            PaymentService payments, Clock clock) {
        this.fares = fares;
        this.payments = payments;
        this.greenJourney = greenJourney;
        this.tickets = tickets;
        this.validations = validations;
        this.stations = stations;
        this.staffStations = staffStations;
        this.clock = clock;
    }

    record Decision(boolean accepted, String reason) {
        static Decision accept(String reason) {
            return new Decision(true, reason);
        }

        static Decision reject(String reason) {
            return new Decision(false, reason);
        }
    }

    @Transactional
    public GateValidationResponse validate(GateValidationRequest request) {
        Station station = stations.findById(request.stationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhà ga"));
        staffStations.requireOnDutyAt(station.getId(), PermissionCodes.STATION_MANAGE);
        UUID operator = CurrentUser.optionalId().orElse(null);
        Instant now = clock.instant();

        Optional<Ticket> found = tickets.findByQrTokenForUpdate(request.qrToken().trim());
        if (found.isEmpty()) {
            return record(null, station, request.direction(), Decision.reject("Mã vé không hợp lệ"), operator, now, null);
        }
        Ticket ticket = found.get();
        Decision decision = request.direction() == GateDirection.ENTRY
                ? checkEntry(ticket, station, now)
                : checkExit(ticket);
        Integer fareDifference = null;
        if (decision.accepted() && request.direction() == GateDirection.EXIT && ticket.isSingleJourney()) {
            int extra = extraFare(ticket, station);
            if (extra > 0) {
                fareDifference = extra;
                if (request.fareAdjustmentMethod() == null) {
                    decision = Decision.reject("Đi quá ga đã mua, cần thu bù " + extra + " đ");
                } else {
                    payments.charge(ticket.getUserId(), PaymentPurpose.TICKET_FARE_ADJUSTMENT, ticket.getId().toString(), extra,
                            request.fareAdjustmentMethod());
                }
            }
        }
        if (decision.accepted()) {
            if (request.direction() == GateDirection.ENTRY) {
                ticket.enter(station, now);
            } else {
                Station entry = ticket.getLastEntryStation();
                Instant entryAt = ticket.getLastEntryAt();
                ticket.exit(now);
                if (greenJourney != null) {
                    greenJourney.recordTrip(ticket.getUserId(), ticket.getId(), entry, entryAt, station, now);
                }
            }
        }
        return record(ticket, station, request.direction(), decision, operator, now, fareDifference);
    }

    Decision checkEntry(Ticket ticket, Station station, Instant now) {
        Optional<String> unusable = unusableReason(ticket);
        if (unusable.isPresent()) {
            return Decision.reject(unusable.get());
        }
        if (ticket.getStatus() == TicketStatus.IN_USE) {
            return Decision.reject("Vé đang trong hành trình (chưa quẹt ra), không thể quẹt vào lần nữa");
        }
        if (ticket.isSingleJourney() && !ticket.getOriginStation().getId().equals(station.getId())) {
            return Decision.reject("Vé lượt chỉ được vào tại ga " + ticket.getOriginStation().getNameStation());
        }
        if (!now.isBefore(ticket.getValidUntil())) {
            ticket.expire();
            return Decision.reject(ticket.getActivatedAt() == null ? "Vé đã quá hạn kích hoạt" : "Vé đã hết hạn");
        }
        if (now.isBefore(ticket.getValidFrom())) {
            return Decision.reject("Vé chưa đến thời gian sử dụng");
        }
        if (station.getStatus() != StationStatus.ACTIVE) {
            return Decision.reject("Ga đang tạm ngừng phục vụ (" + station.getStatus() + ")");
        }
        return Decision.accept(ticket.getActivatedAt() == null ? "Kích hoạt vé, chúc quý khách thượng lộ bình an" : null);
    }

    /** Khách đã ở trong ga thì luôn được ra kể cả khi vé hết hạn trong lúc đi. */
    Decision checkExit(Ticket ticket) {
        Optional<String> unusable = unusableReason(ticket);
        if (unusable.isPresent()) {
            return Decision.reject(unusable.get());
        }
        if (ticket.getStatus() != TicketStatus.IN_USE) {
            return Decision.reject("Vé chưa được quẹt vào, vui lòng liên hệ nhân viên");
        }
        return Decision.accept(null);
    }

    private static Optional<String> unusableReason(Ticket ticket) {
        return switch (ticket.getStatus()) {
            case PENDING_PAYMENT -> Optional.of("Vé chưa được thanh toán");
            case CANCELLED -> Optional.of("Vé đã bị huỷ");
            case USED -> Optional.of("Vé lượt đã được sử dụng");
            case REFUNDED -> Optional.of("Vé đã được hoàn tiền");
            case EXPIRED -> Optional.of("Vé đã hết hạn");
            default -> Optional.empty();
        };
    }

    /** Chênh lệch so với giá đã mua, tính từ ga thực tế vào đến ga ra. Ra sớm hơn thì bằng 0. */
    private int extraFare(Ticket ticket, Station exit) {
        Station entry = ticket.getLastEntryStation();
        if (entry.getId().equals(exit.getId())) {
            return 0;
        }
        return fares.quote(entry.getId(), exit.getId())
                .map(q -> Math.max(0, q.priceVnd() - ticket.getListUnitPriceVnd()))
                .orElse(0);
    }

    private GateValidationResponse record(Ticket ticket, Station station, GateDirection direction, Decision decision,
            UUID operator, Instant now, Integer fareDifference) {
        GateResult result = decision.accepted() ? GateResult.ACCEPTED : GateResult.REJECTED;
        TicketValidation saved = validations.save(new TicketValidation(ticket, station, direction, result, decision.reason(), operator, now, fareDifference));
        if (decision.accepted() && direction == GateDirection.ENTRY && events != null) {
            events.publishEvent(new CheckInAccepted(ticket.getUserId(), saved.getId()));
        }
        return new GateValidationResponse(result, decision.reason(), direction, StationRef.of(station),
                ticket == null ? null : ticket.getTicketCode(),
                ticket == null ? null : ticket.getProduct().getCode(),
                ticket == null ? null : ticket.getStatus(),
                ticket == null ? null : ticket.getValidUntil(), now, fareDifference);
    }
}
