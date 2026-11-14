package com.metroticket_api.ticket;

import java.math.BigDecimal;
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
import com.metroticket_api.ticket.TicketDtos.GateInspectResponse;
import com.metroticket_api.ticket.TicketDtos.GateValidationRequest;
import com.metroticket_api.ticket.TicketDtos.GateValidationResponse;
import com.metroticket_api.ticket.TicketDtos.StationRef;

/**
 * Soát vé tại cổng: nhân viên ga quét QR trên app hành khách để check-in (quẹt vào) / check-out (quẹt ra).
 * Vé theo thời gian vào/ra ở ga nào cũng được; vé lượt phải vào đúng ga đi, dùng một lần, ra quá ga đã mua thì thu
 * bù chênh lệch (tính theo đơn giá/km lúc mua), đi ít hơn không hoàn tiền.
 *
 * <p><b>Check-in</b>: vé ACTIVE, còn hạn (chưa kích hoạt: còn hạn kích hoạt), ga đang ACTIVE. Vé đang IN_USE bị chặn
 * (chống dùng 1 vé cho 2 người – anti-passback). Lần check-in đầu tiên kích hoạt vé.</p>
 * <p><b>Check-out</b>: vé phải IN_USE; đã ở trong ga thì luôn được ra kể cả vé vừa hết hạn.</p>
 * <p>Mọi lượt quét (kể cả bị từ chối) được ghi vào ticket_validation; từ chối trả HTTP 200 với result = REJECTED.
 * Tra cứu (inspect) chỉ xem trước kết quả, không đổi trạng thái vé và không ghi nhật ký.</p>
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
        Station station = requireStation(request.stationId());
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
        if (!decision.accepted() && request.direction() == GateDirection.ENTRY
                && ticket.getStatus() == TicketStatus.ACTIVE && !now.isBefore(ticket.getValidUntil())) {
            ticket.expire();
        }
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

    /**
     * Nhân viên quét QR để xem vé và kết quả dự kiến tại ga này trước khi cho qua: hướng tiếp theo (vào/ra), có được
     * chấp nhận không, số tiền phải thu bù nếu vé lượt ra quá ga. Không đổi trạng thái vé.
     */
    @Transactional(readOnly = true)
    public GateInspectResponse inspect(String qrToken, Long stationId) {
        Station station = requireStation(stationId);
        staffStations.requireOnDutyAt(station.getId(), PermissionCodes.STATION_MANAGE);
        Ticket ticket = tickets.findByQrToken(qrToken.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mã vé không hợp lệ"));
        GateDirection next = ticket.getStatus() == TicketStatus.IN_USE ? GateDirection.EXIT : GateDirection.ENTRY;
        Instant now = clock.instant();
        Decision decision = next == GateDirection.ENTRY ? checkEntry(ticket, station, now) : checkExit(ticket);
        Integer fareDifference = null;
        if (decision.accepted() && next == GateDirection.EXIT && ticket.isSingleJourney()) {
            int extra = extraFare(ticket, station);
            if (extra > 0) {
                fareDifference = extra;
                decision = Decision.reject("Đi quá ga đã mua, cần thu bù " + extra + " đ");
            }
        }
        return new GateInspectResponse(next, decision.accepted() ? GateResult.ACCEPTED : GateResult.REJECTED,
                decision.reason(), StationRef.of(station), ticket.getTicketCode(), ticket.getProduct().getCode(),
                ticket.getProduct().getName(), ticket.getStatus(), ticket.isSingleJourney(),
                StationRef.of(ticket.getOriginStation()), StationRef.of(ticket.getDestinationStation()),
                StationRef.of(ticket.getLastEntryStation()), ticket.getLastEntryAt(), ticket.getValidUntil(),
                fareDifference);
    }

    /** Không đổi trạng thái vé; vé quá hạn được chuyển EXPIRED ở {@link #validate}. */
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

    /**
     * Chênh lệch giữa giá quãng đường thực tế (ga vào → ga ra) và giá đã mua, tính theo đơn giá/km lúc mua.
     * Ra sớm hơn hoặc ra đúng ga thì bằng 0.
     */
    int extraFare(Ticket ticket, Station exit) {
        Station entry = ticket.getLastEntryStation();
        if (entry == null || entry.getId().equals(exit.getId())
                || exit.getId().equals(ticket.getDestinationStation().getId()) && entry.getId().equals(ticket.getOriginStation().getId())) {
            return 0;
        }
        Optional<BigDecimal> km = fares.distanceKm(entry.getId(), exit.getId());
        if (km.isEmpty() || km.get().compareTo(ticket.getDistanceKm()) <= 0) {
            return 0;
        }
        int perKm = ticket.getFarePerKmVnd() != null ? ticket.getFarePerKmVnd()
                : fares.quote(ticket.getProduct().getCode(), entry.getId(), exit.getId())
                        .map(FareService.Quote::farePerKmVnd).orElse(0);
        if (perKm <= 0) {
            return 0;
        }
        return Math.max(0, fares.priceFor(km.get(), perKm) - ticket.getListUnitPriceVnd());
    }

    private Station requireStation(Long stationId) {
        return stations.findById(stationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhà ga"));
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
