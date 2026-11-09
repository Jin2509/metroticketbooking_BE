package com.metroticket_api.ticket;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.criteria.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.loyalty.LoyaltyService;
import com.metroticket_api.metro.common.SortGuard;
import com.metroticket_api.payment.PaymentMethod;
import com.metroticket_api.payment.PaymentOrder;
import com.metroticket_api.payment.PaymentOrderService;
import com.metroticket_api.payment.PaymentProps;
import com.metroticket_api.payment.PaymentPurpose;
import com.metroticket_api.payment.PaymentService;
import com.metroticket_api.rbac.PermissionCodes;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.student.StudentVerificationService;
import com.metroticket_api.ticket.TicketDtos.PurchaseTicketRequest;
import com.metroticket_api.ticket.TicketDtos.TicketResponse;
import com.metroticket_api.ticket.TicketDtos.ValidationResponse;

/**
 * Mua vé, xem vé. Bán vé theo thời gian (1 ngày, 3 ngày, 1 tuần, 1 tháng...) và vé lượt giữa hai ga.
 *
 * <ul>
 *   <li>Giá = giá một kỳ × số kỳ; vé tháng mua được nhiều tháng (tối đa maxPeriods của loại vé), các loại khác 1 kỳ.</li>
 *   <li>Phải kích hoạt (quẹt vào lần đầu) trong activation_window_days kể từ lúc mua. Hạn dùng tính từ lần quẹt
 *       đầu: ngày/tuần theo đủ 24 giờ, tháng theo lịch (giờ Việt Nam). Mua 3 tháng → hạn = kích hoạt + 3 tháng.</li>
 *   <li>Vé lưu snapshot thời lượng, số kỳ, đơn giá: admin đổi loại vé sau đó không ảnh hưởng vé đã bán.</li>
 *   <li>Vé lượt: giá theo km lộ trình ngắn nhất (FareService), dùng được đến hết ngày mua, không có ưu đãi sinh viên.</li>
 *   <li>Vé đã mua không được hoàn tiền (đơn chưa thanh toán thì huỷ và hoàn lại điểm đã trừ).</li>
 *   <li>Có thể dùng điểm CPoint giảm giá: 1 điểm = 1 đồng, không vượt số dư và mức giảm tối đa.</li>
 *   <li>Mua online chỉ bằng SEPAY_QR (giả lập CARD/E_WALLET chỉ ở dev).</li>
 * </ul>
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);
    /** Vé đã quẹt vào nhưng không quẹt ra: sau mốc này kể từ hạn dùng thì chuyển EXPIRED. */
    static final Duration STUCK_IN_USE_GRACE = Duration.ofHours(24);
    static final String POINTS_REFERENCE = "TICKET";
    public static final java.util.Set<String> SORTABLE_FIELDS = java.util.Set.of("purchasedAt", "validUntil", "priceVnd", "status");

    private final TicketRepository tickets;
    private final TicketProductRepository products;
    private final TicketValidationRepository validations;
    private final PaymentService payments;
    private final PaymentOrderService paymentOrders;
    private final PaymentProps paymentProps;
    private final StudentVerificationService studentVerifications;
    private final Clock clock;
    private final LoyaltyService loyalty;
    private final FareService fares;
    private final com.metroticket_api.metro.station.StationRepository stations;

    public TicketService(TicketRepository tickets, TicketProductRepository products,
            TicketValidationRepository validations, PaymentService payments, PaymentOrderService paymentOrders,
            PaymentProps paymentProps, StudentVerificationService studentVerifications, LoyaltyService loyalty,
            FareService fares, com.metroticket_api.metro.station.StationRepository stations, Clock clock) {
        this.loyalty = loyalty;
        this.fares = fares;
        this.stations = stations;
        this.studentVerifications = studentVerifications;
        this.paymentOrders = paymentOrders;
        this.paymentProps = paymentProps;
        this.tickets = tickets;
        this.products = products;
        this.validations = validations;
        this.payments = payments;
        this.clock = clock;
    }

    @Transactional
    public TicketResponse purchase(PurchaseTicketRequest request) {
        UUID userId = CurrentUser.id();
        PaymentMethod method = request.paymentMethod();
        boolean qr = method == PaymentMethod.SEPAY_QR;
        if (!qr && !(method.isMockOnline() && paymentProps.mockEnabled())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mua vé online chỉ hỗ trợ thanh toán SEPAY_QR");
        }
        TicketProduct product = products.findByCode(request.productCode().trim().toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy loại vé"));
        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Loại vé đang ngừng bán");
        }
        int periods = request.periods() == null ? 1 : request.periods();
        if (periods > product.getMaxPeriods()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, product.getMaxPeriods() == 1
                    ? "Loại vé " + product.getName() + " chỉ mua được 1 kỳ mỗi lần"
                    : "Mua tối đa " + product.getMaxPeriods() + " kỳ mỗi lần");
        }
        Instant now = clock.instant();
        if (product.isSingleJourney() && Boolean.TRUE.equals(request.studentDiscount())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vé lượt không áp dụng ưu đãi sinh viên");
        }
        StudentVerificationService.StudentDiscount student = !product.isSingleJourney() && !Boolean.FALSE.equals(request.studentDiscount())
                ? studentVerifications.discountFor(userId, product.getCode()).orElse(null)
                : null;
        if (Boolean.TRUE.equals(request.studentDiscount()) && student == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bạn chưa được xác thực sinh viên hoặc loại vé này không có ưu đãi sinh viên");
        }
        if (student != null) {
            ensureWithinStudentValidity(product, periods, student, now);
        }
        Ticket ticket = new Ticket(userId, product, periods, now,
                product.isSingleJourney() ? endOfDay(now) : now.plus(Duration.ofDays(product.getActivationWindowDays())),
                student == null ? null : "STUDENT", student == null ? 0 : student.percent());
        if (product.isSingleJourney()) {
            assignJourney(ticket, request);
        } else if (request.originStationId() != null || request.destinationStationId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ vé lượt mới chọn ga đi, ga đến");
        }
        int points = request.pointsToUse() == null ? 0 : request.pointsToUse();
        ticket.applyPoints(loyalty.redeem(userId, points, ticket.getPriceVnd(), POINTS_REFERENCE, ticket.getId().toString()));
        if (ticket.getPriceVnd() == 0) {
            // Ưu đãi 100%: không có gì để thanh toán, cấp vé luôn
            tickets.save(ticket);
            return TicketResponse.of(ticket, true, false);
        }
        if (qr) {
            // Giữ chỗ vé tới khi tiền về; QR của vé chỉ trả ra sau khi thanh toán
            ticket.awaitPayment();
            tickets.save(ticket);
            PaymentOrder order = paymentOrders.createOrder(userId, PaymentPurpose.TICKET, ticket.getId().toString(),
                    ticket.getPriceVnd());
            return TicketResponse.of(ticket, true, false, paymentOrders.instructionOf(order));
        }
        // Chỉ môi trường dev (app.payment.mock-enabled=true): coi như thanh toán thành công ngay
        tickets.save(ticket);
        payments.charge(userId, PaymentPurpose.TICKET, ticket.getId().toString(), ticket.getPriceVnd(), method);
        return TicketResponse.of(ticket, true, false);
    }

    @Transactional(readOnly = true)
    public Page<TicketResponse> myTickets(TicketStatus status, String productCode, Pageable pageable) {
        UUID userId = CurrentUser.id();
        Specification<Ticket> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (productCode != null && !productCode.isBlank()) {
                predicates.add(cb.equal(root.get("product").get("code"), productCode.trim().toUpperCase()));
            }
            query.orderBy(cb.desc(root.get("purchasedAt")));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Pageable unsorted = org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return tickets.findAll(spec, unsorted).map(t -> TicketResponse.of(t, true, false, pendingInstruction(t)));
    }

    @Transactional(readOnly = true)
    public TicketResponse get(UUID ticketId) {
        Ticket ticket = requireReadable(ticketId);
        boolean owner = CurrentUser.optionalId().map(ticket.getUserId()::equals).orElse(false);
        return TicketResponse.of(ticket, owner, false, owner ? pendingInstruction(ticket) : null);
    }

    @Transactional(readOnly = true)
    public List<ValidationResponse> history(UUID ticketId) {
        requireReadable(ticketId);
        return validations.findByTicketId(ticketId).stream().map(ValidationResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public Page<TicketResponse> search(TicketStatus status, String productCode, UUID userId, String ticketCode,
            Pageable pageable) {
        SortGuard.requireAllowed(pageable, SORTABLE_FIELDS);
        Specification<Ticket> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (productCode != null && !productCode.isBlank()) {
                predicates.add(cb.equal(root.get("product").get("code"), productCode.trim().toUpperCase()));
            }
            if (userId != null) predicates.add(cb.equal(root.get("userId"), userId));
            if (ticketCode != null && !ticketCode.isBlank()) {
                predicates.add(cb.equal(root.get("ticketCode"), ticketCode.trim().toUpperCase()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return tickets.findAll(spec, pageable).map(t -> TicketResponse.of(t, false, false));
    }

    private void assignJourney(Ticket ticket, PurchaseTicketRequest request) {
        if (request.originStationId() == null || request.destinationStationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vé lượt phải chọn ga đi và ga đến");
        }
        if (request.originStationId().equals(request.destinationStationId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ga đi và ga đến phải khác nhau");
        }
        com.metroticket_api.metro.station.Station origin = requireActiveStation(request.originStationId());
        com.metroticket_api.metro.station.Station destination = requireActiveStation(request.destinationStationId());
        FareService.Quote quote = fares.quote(origin.getId(), destination.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lộ trình phù hợp"));
        ticket.assignJourney(origin, destination, quote.distanceKm(), quote.priceVnd());
    }

    private com.metroticket_api.metro.station.Station requireActiveStation(Long id) {
        com.metroticket_api.metro.station.Station station = stations.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhà ga"));
        if (station.getStatus() != com.metroticket_api.metro.common.StationStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ga " + station.getNameStation() + " đang tạm ngừng phục vụ");
        }
        return station;
    }

    /** 00:00 ngày hôm sau theo giờ Việt Nam. */
    static Instant endOfDay(Instant now) {
        java.time.ZoneId vietnam = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        return java.time.LocalDate.ofInstant(now, vietnam).plusDays(1).atStartOfDay(vietnam).toInstant();
    }

    /** Đánh dấu vé hết hạn định kỳ (vé vẫn bị chặn ở cổng ngay cả khi job chưa chạy). */
    @Scheduled(fixedDelayString = "${app.ticket.expiry-job-interval:10m}", initialDelayString = "${app.ticket.expiry-job-interval:10m}")
    @Transactional
    public void expireTickets() {
        Instant now = clock.instant();
        int unused = tickets.expireUnused(now);
        int stuck = tickets.expireStuckInUse(now.minus(STUCK_IN_USE_GRACE));
        if (unused + stuck > 0) {
            log.info("Đã chuyển {} vé hết hạn và {} vé kẹt IN_USE sang EXPIRED", unused, stuck);
        }
    }

    /**
     * Giá sinh viên chỉ cho phần thời gian còn được xác thực: mua 6 tháng mà ưu đãi còn 2 tháng → 400, gợi ý số kỳ tối đa
     * (hoặc gửi studentDiscount=false để mua giá thường).
     */
    static void ensureWithinStudentValidity(TicketProduct product, int periods,
            StudentVerificationService.StudentDiscount student, Instant now) {
        java.time.LocalDate today = java.time.LocalDate.ofInstant(now, java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        java.time.LocalDate end = switch (product.getDurationUnit()) {
            case DAY -> today.plusDays((long) product.getDurationValue() * periods);
            case WEEK -> today.plusWeeks((long) product.getDurationValue() * periods);
            case MONTH -> today.plusMonths((long) product.getDurationValue() * periods);
        };
        if (end.isAfter(student.validUntil().plusDays(1))) {
            long maxPeriods = product.getDurationUnit() == DurationUnit.MONTH
                    ? java.time.temporal.ChronoUnit.MONTHS.between(today, student.validUntil().plusDays(1)) / product.getDurationValue()
                    : 0;
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ưu đãi sinh viên còn đến "
                    + student.validUntil() + ", chỉ mua giá sinh viên tối đa " + Math.max(0, maxPeriods)
                    + " kỳ; hoặc chọn mua giá thường (studentDiscount=false)");
        }
    }

    private com.metroticket_api.payment.PaymentOrderService.PaymentInstruction pendingInstruction(Ticket t) {
        return t.getStatus() == TicketStatus.PENDING_PAYMENT
                ? paymentOrders.instructionFor(PaymentPurpose.TICKET, t.getId().toString()).orElse(null)
                : null;
    }

    private Ticket requireReadable(UUID ticketId) {
        Ticket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy vé"));
        boolean owner = CurrentUser.optionalId().map(ticket.getUserId()::equals).orElse(false);
        if (!owner && !CurrentUser.hasAuthority(PermissionCodes.TICKET_VIEW_ALL)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy vé");
        }
        return ticket;
    }
}
