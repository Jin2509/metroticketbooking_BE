package com.metroticket_api.parking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.staff.StaffStationService;
import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.parking.ParkingDtos.BuyMonthlyPassRequest;
import com.metroticket_api.parking.ParkingDtos.CheckInRequest;
import com.metroticket_api.parking.ParkingDtos.CheckOutRequest;
import com.metroticket_api.parking.ParkingDtos.CreateLotRequest;
import com.metroticket_api.parking.ParkingDtos.LotResponse;
import com.metroticket_api.parking.ParkingDtos.MonthlyPassResponse;
import com.metroticket_api.parking.ParkingDtos.RateAvailability;
import com.metroticket_api.parking.ParkingDtos.SessionResponse;
import com.metroticket_api.parking.ParkingDtos.UpdateLotRequest;
import com.metroticket_api.parking.ParkingDtos.UpsertRateRequest;
import com.metroticket_api.payment.PaymentMethod;
import com.metroticket_api.payment.PaymentOrder;
import com.metroticket_api.payment.PaymentOrderService;
import com.metroticket_api.payment.PaymentProps;
import com.metroticket_api.payment.PaymentPurpose;
import com.metroticket_api.payment.PaymentService;
import com.metroticket_api.rbac.PermissionCodes;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.ticket.Ticket;
import com.metroticket_api.ticket.TicketRepository;
import com.metroticket_api.ticket.TicketValidationRepository;

/**
 * Gửi xe tại ga (Park &amp; Ride).
 *
 * <ul>
 *   <li><b>Xe vào</b>: bãi ACTIVE và đang mở cửa; loại xe được bãi nhận; còn chỗ (khoá dòng sức chứa để không
 *       vượt khi nhiều cổng cùng nhận xe); một biển số không thể đang gửi ở 2 nơi; xe máy/ô tô bắt buộc biển số.
 *       Có vé tháng thì vé phải đúng bãi, đúng loại xe, đúng biển số và còn hạn.</li>
 *   <li><b>Xe ra</b>: đối chiếu biển số; phí = lượt ngày + số đêm × giá qua đêm. Vé tháng miễn phí trong thời hạn
 *       vé (quá hạn khi đang gửi thì tính phần vượt). Ưu đãi Park &amp; Ride: xuất trình vé Metro đã được soát
 *       thành công tại chính ga của bãi trong thời gian gửi xe → giảm theo % cấu hình.</li>
 *   <li><b>Vé tháng</b>: 30 ngày từ lúc mua; không trùng thời gian với vé khác cùng biển số tại bãi; số vé tháng
 *       còn hạn không vượt sức chứa của loại xe.</li>
 * </ul>
 */
@Service
public class ParkingService {

    static final Duration MONTHLY_PASS_DURATION = Duration.ofDays(30);
    /** Khách có thể quẹt vé vào ga ngay trước khi gửi xe (ví dụ gửi xe sau khi mua vé ở cổng). */
    static final Duration PARK_AND_RIDE_LOOKBACK = Duration.ofHours(2);

    private final ParkingLotRepository lots;
    private final ParkingRateRepository rates;
    private final ParkingSessionRepository sessions;
    private final ParkingMonthlyPassRepository passes;
    private final StationRepository stations;
    private final TicketRepository tickets;
    private final TicketValidationRepository validations;
    private final StaffStationService staffStations;
    private final PaymentService payments;
    private final PaymentOrderService paymentOrders;
    private final PaymentProps paymentProps;
    private final ParkingBookingRepository bookings;
    private final Clock clock;

    public ParkingService(ParkingLotRepository lots, ParkingRateRepository rates, ParkingSessionRepository sessions,
            ParkingMonthlyPassRepository passes, StationRepository stations, TicketRepository tickets,
            TicketValidationRepository validations, StaffStationService staffStations, PaymentService payments,
            PaymentOrderService paymentOrders, PaymentProps paymentProps, ParkingBookingRepository bookings, Clock clock) {
        this.bookings = bookings;
        this.paymentOrders = paymentOrders;
        this.paymentProps = paymentProps;
        this.lots = lots;
        this.rates = rates;
        this.sessions = sessions;
        this.passes = passes;
        this.stations = stations;
        this.tickets = tickets;
        this.validations = validations;
        this.staffStations = staffStations;
        this.payments = payments;
        this.clock = clock;
    }

    // ===================== Bãi xe & bảng giá =====================

    @Transactional(readOnly = true)
    public List<LotResponse> listLots(Long stationId) {
        List<ParkingLot> found = stationId == null ? lots.findAllWithStation() : lots.findByStationWithStation(stationId);
        if (found.isEmpty()) {
            return List.of();
        }
        List<Long> ids = found.stream().map(ParkingLot::getId).toList();
        Map<Long, List<ParkingRate>> ratesByLot = new HashMap<>();
        for (ParkingRate rate : rates.findByLotIdIn(ids)) {
            ratesByLot.computeIfAbsent(rate.getLot().getId(), k -> new java.util.ArrayList<>()).add(rate);
        }
        Map<String, Long> occupied = new HashMap<>();
        for (Object[] row : sessions.countParkedByLot(ids)) {
            occupied.put(row[0] + ":" + row[1], (Long) row[2]);
        }
        Instant now = clock.instant();
        return found.stream().map(lot -> toLotResponse(lot, ratesByLot.getOrDefault(lot.getId(), List.of()), occupied, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public LotResponse getLot(Long lotId) {
        ParkingLot lot = requireLot(lotId);
        return listLots(lot.getStation().getId()).stream().filter(l -> l.id().equals(lotId)).findFirst().orElseThrow();
    }

    @Transactional
    public LotResponse createLot(CreateLotRequest request) {
        Station station = stations.findById(request.stationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy nhà ga"));
        String code = request.code().trim().toUpperCase();
        if (lots.existsByCode(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã bãi xe đã tồn tại");
        }
        validateHours(request.openTime(), request.closeTime());
        ParkingLot lot = lots.save(new ParkingLot(station, code, request.name().trim(), blankToNull(request.address()),
                request.openTime(), request.closeTime(), request.status()));
        return toLotResponse(lot, List.of(), Map.of(), clock.instant());
    }

    @Transactional
    public LotResponse updateLot(Long lotId, UpdateLotRequest request) {
        ParkingLot lot = requireLot(lotId);
        if (request.name() != null && !request.name().isBlank()) lot.setName(request.name().trim());
        if (request.address() != null) lot.setAddress(blankToNull(request.address()));
        if (request.openTime() != null) lot.setOpenTime(request.openTime());
        if (request.closeTime() != null) lot.setCloseTime(request.closeTime());
        if (request.status() != null) lot.setStatus(request.status());
        validateHours(lot.getOpenTime(), lot.getCloseTime());
        return getLot(lotId);
    }

    /** Thêm/sửa sức chứa và giá của 1 loại xe. Không cho giảm sức chứa xuống dưới số xe đang gửi. */
    @Transactional
    public LotResponse upsertRate(Long lotId, VehicleType vehicleType, UpsertRateRequest request) {
        ParkingLot lot = requireLot(lotId);
        ParkingRate rate = rates.findForUpdate(lotId, vehicleType).orElseGet(() -> new ParkingRate(lot, vehicleType));
        long parked = sessions.countByLotIdAndVehicleTypeAndStatus(lotId, vehicleType, ParkingSessionStatus.PARKED);
        if (request.capacity() < parked) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Sức chứa mới (" + request.capacity() + ") nhỏ hơn số xe đang gửi (" + parked + ")");
        }
        rate.update(request.capacity(), request.dayPriceVnd(), request.overnightPriceVnd(), request.monthlyPriceVnd(),
                request.parkAndRideDiscountPercent() == null ? 0 : request.parkAndRideDiscountPercent());
        rates.save(rate);
        return getLot(lotId);
    }

    // ===================== Xe vào / xe ra =====================

    @Transactional
    public SessionResponse checkIn(CheckInRequest request) {
        ParkingLot lot = requireLot(request.lotId());
        staffStations.requireOnDutyAt(lot.getStation().getId(), PermissionCodes.PARKING_MANAGE);
        Instant now = clock.instant();
        if (lot.getStatus() != ParkingLotStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bãi xe đang " + lot.getStatus() + ", không nhận xe");
        }
        if (!ParkingFeeCalculator.isOpen(lot.getOpenTime(), lot.getCloseTime(), now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ngoài giờ nhận xe (" + lot.getOpenTime() + " – " + lot.getCloseTime() + ")");
        }
        String plate = PlateNumbers.require(request.plateNumber(), request.vehicleType());

        ParkingMonthlyPass pass = null;
        if (request.monthlyPassCode() != null && !request.monthlyPassCode().isBlank()) {
            pass = passes.findByPassCode(request.monthlyPassCode().trim().toUpperCase())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy vé tháng"));
            if (!pass.getLot().getId().equals(lot.getId()) || pass.getVehicleType() != request.vehicleType()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Vé tháng không áp dụng cho bãi/loại xe này");
            }
            if (!pass.getPlateNumber().equals(plate)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Biển số không khớp với vé tháng");
            }
            if (!pass.isValidAt(now)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Vé tháng đã hết hạn hoặc bị huỷ");
            }
        }

        ParkingRate rate = rates.findForUpdate(lot.getId(), request.vehicleType())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Bãi không nhận loại xe " + request.vehicleType()));
        long parked = sessions.countByLotIdAndVehicleTypeAndStatus(lot.getId(), request.vehicleType(),
                ParkingSessionStatus.PARKED);
        if (parked + bookings.countHolding(lot.getId(), request.vehicleType(), now, now) >= rate.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bãi đã hết chỗ cho " + request.vehicleType());
        }
        if (plate != null && sessions.existsByPlateNumberAndStatus(plate, ParkingSessionStatus.PARKED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Xe biển số " + plate + " đang được gửi, chưa lấy ra");
        }

        ParkingSession session = sessions.save(new ParkingSession(lot, request.vehicleType(), plate, pass,
                CurrentUser.optionalId().orElse(null), now));
        return toSessionResponse(session, estimate(session, rate, now));
    }

    /** Xem lượt gửi và phí tạm tính trước khi cho xe ra. */
    @Transactional(readOnly = true)
    public SessionResponse getSession(String sessionCode) {
        ParkingSession session = sessions.findBySessionCode(sessionCode.trim().toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lượt gửi xe"));
        staffStations.requireOnDutyAt(session.getLot().getStation().getId(), PermissionCodes.PARKING_MANAGE);
        Integer estimated = null;
        if (session.getStatus() == ParkingSessionStatus.PARKED) {
            estimated = rates.findByLotIdAndVehicleType(session.getLot().getId(), session.getVehicleType())
                    .map(rate -> estimate(session, rate, clock.instant()))
                    .orElse(null);
        }
        return toSessionResponse(session, estimated);
    }

    @Transactional
    public SessionResponse checkOut(CheckOutRequest request) {
        ParkingSession session = sessions.findByCodeForUpdate(request.sessionCode().trim().toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lượt gửi xe"));
        ParkingLot lot = session.getLot();
        staffStations.requireOnDutyAt(lot.getStation().getId(), PermissionCodes.PARKING_MANAGE);
        if (session.getStatus() != ParkingSessionStatus.PARKED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Xe đã được lấy ra lúc " + session.getCheckOutAt());
        }
        if (session.getPlateNumber() != null) {
            String plate = PlateNumbers.normalize(request.plateNumber());
            if (!session.getPlateNumber().equals(plate)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Biển số không khớp với lượt gửi xe");
            }
        }
        Instant now = clock.instant();
        ParkingRate rate = rates.findByLotIdAndVehicleType(lot.getId(), session.getVehicleType())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Bãi chưa có bảng giá cho loại xe này"));
        int fee = estimate(session, rate, now);

        Ticket metroTicket = null;
        int discount = 0;
        if (fee > 0 && request.metroTicketQrToken() != null && !request.metroTicketQrToken().isBlank()) {
            metroTicket = requireParkAndRideTicket(request.metroTicketQrToken().trim(), session, now);
            discount = ParkingFeeCalculator.discount(fee, rate.getParkAndRideDiscountPercent());
        }
        int payable = fee - discount;
        PaymentMethod method = null;
        if (payable > 0) {
            method = request.paymentMethod();
            if (method == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phí gửi xe " + payable + " đ: chọn phương thức thanh toán");
            }
            UUID payer = session.getMonthlyPass() != null ? session.getMonthlyPass().getUserId()
                    : metroTicket != null ? metroTicket.getUserId() : null;
            payments.charge(payer, PaymentPurpose.PARKING_SESSION, session.getId().toString(), payable, method);
        }
        session.complete(now, fee, discount, method, metroTicket, CurrentUser.optionalId().orElse(null));
        return toSessionResponse(session, null);
    }

    @Transactional(readOnly = true)
    public Page<SessionResponse> lotSessions(Long lotId, ParkingSessionStatus status, Pageable pageable) {
        ParkingLot lot = requireLot(lotId);
        staffStations.requireOnDutyAt(lot.getStation().getId(), PermissionCodes.PARKING_MANAGE);
        Page<ParkingSession> page = status == null
                ? sessions.findByLotIdOrderByCheckInAtDesc(lotId, pageable)
                : sessions.findByLotIdAndStatusOrderByCheckInAtDesc(lotId, status, pageable);
        return page.map(s -> toSessionResponse(s, null));
    }

    // ===================== Vé tháng =====================

    @Transactional
    public MonthlyPassResponse buyMonthlyPass(BuyMonthlyPassRequest request) {
        UUID userId = CurrentUser.id();
        PaymentMethod method = request.paymentMethod();
        boolean qr = method == PaymentMethod.SEPAY_QR;
        if (!qr && !(method.isMockOnline() && paymentProps.mockEnabled())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mua vé tháng online chỉ hỗ trợ thanh toán SEPAY_QR");
        }
        ParkingLot lot = requireLot(request.lotId());
        if (lot.getStatus() != ParkingLotStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bãi xe đang " + lot.getStatus() + ", không bán vé tháng");
        }
        String plate = PlateNumbers.require(request.plateNumber(), request.vehicleType());
        if (plate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vé tháng phải gắn với biển số / mã xe");
        }
        ParkingRate rate = rates.findForUpdate(lot.getId(), request.vehicleType())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Bãi không nhận loại xe " + request.vehicleType()));
        Instant now = clock.instant();
        Instant until = now.plus(MONTHLY_PASS_DURATION);
        if (passes.countOverlapping(lot.getId(), plate, now, until) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Xe này đã có vé tháng còn hạn tại bãi");
        }
        if (passes.countActive(lot.getId(), request.vehicleType(), now) >= rate.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bãi đã hết suất vé tháng cho " + request.vehicleType());
        }
        ParkingMonthlyPass pass = new ParkingMonthlyPass(userId, lot, request.vehicleType(), plate,
                rate.getMonthlyPriceVnd(), now, until);
        if (qr) {
            pass.awaitPayment();
            passes.save(pass);
            PaymentOrder order = paymentOrders.createOrder(userId, PaymentPurpose.PARKING_MONTHLY_PASS,
                    pass.getId().toString(), pass.getPriceVnd());
            return toPassResponse(pass, now, paymentOrders.instructionOf(order));
        }
        passes.save(pass);
        payments.charge(userId, PaymentPurpose.PARKING_MONTHLY_PASS, pass.getId().toString(), pass.getPriceVnd(), method);
        return toPassResponse(pass, now);
    }

    @Transactional(readOnly = true)
    public List<MonthlyPassResponse> myPasses() {
        Instant now = clock.instant();
        return passes.findByUserIdOrderByValidUntilDesc(CurrentUser.id()).stream()
                .map(p -> toPassResponse(p, now, p.getStatus() == MonthlyPassStatus.PENDING_PAYMENT
                        ? paymentOrders.instructionFor(PaymentPurpose.PARKING_MONTHLY_PASS, p.getId().toString()).orElse(null)
                        : null))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<SessionResponse> mySessions(Pageable pageable) {
        return sessions.findByMonthlyPassUserIdOrderByCheckInAtDesc(CurrentUser.id(), pageable)
                .map(s -> toSessionResponse(s, null));
    }

    // ===================== Helpers =====================

    /** Phí tại thời điểm {@code at}; vé tháng còn hạn lúc gửi chỉ tính phần vượt quá hạn vé. */
    static int estimate(ParkingSession session, ParkingRate rate, Instant at) {
        Instant chargeFrom = session.getCheckInAt();
        ParkingMonthlyPass pass = session.getMonthlyPass();
        if (pass != null && pass.isValidAt(session.getCheckInAt())) {
            if (!at.isAfter(pass.getValidUntil())) {
                return 0;
            }
            chargeFrom = pass.getValidUntil();
        }
        return ParkingFeeCalculator.fee(rate.getDayPriceVnd(), rate.getOvernightPriceVnd(), chargeFrom, at);
    }

    private Ticket requireParkAndRideTicket(String qrToken, ParkingSession session, Instant now) {
        Ticket ticket = tickets.findByQrToken(qrToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vé Metro không hợp lệ"));
        Long stationId = session.getLot().getStation().getId();
        boolean used = validations.countAcceptedAtStationBetween(ticket.getId(), stationId,
                session.getCheckInAt().minus(PARK_AND_RIDE_LOOKBACK), now) > 0;
        if (!used) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Vé Metro chưa được soát tại ga " + session.getLot().getStation().getNameStation()
                            + " trong thời gian gửi xe nên không được ưu đãi Park & Ride");
        }
        // Một vé ngày/tuần/tháng gửi được nhiều xe nếu không giới hạn → mỗi vé chỉ được ưu đãi 1 lượt gửi xe mỗi ngày
        java.time.LocalDate today = java.time.LocalDate.ofInstant(now, ParkingFeeCalculator.VIETNAM);
        Instant dayStart = today.atStartOfDay(ParkingFeeCalculator.VIETNAM).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(ParkingFeeCalculator.VIETNAM).toInstant();
        if (sessions.countByMetroTicketIdAndCheckOutAtGreaterThanEqualAndCheckOutAtLessThan(ticket.getId(), dayStart,
                dayEnd) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vé Metro này đã được ưu đãi gửi xe trong hôm nay");
        }
        return ticket;
    }

    private ParkingLot requireLot(Long lotId) {
        return lots.findById(lotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bãi xe"));
    }

    private static void validateHours(java.time.LocalTime open, java.time.LocalTime close) {
        if (open.equals(close)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giờ mở và giờ đóng cửa không được trùng nhau");
        }
    }

    private static LotResponse toLotResponse(ParkingLot lot, List<ParkingRate> lotRates, Map<String, Long> occupied,
            Instant now) {
        List<RateAvailability> vehicles = lotRates.stream()
                .sorted(java.util.Comparator.comparing(ParkingRate::getVehicleType))
                .map(rate -> {
                    long used = occupied.getOrDefault(lot.getId() + ":" + rate.getVehicleType(), 0L);
                    return new RateAvailability(rate.getVehicleType(), rate.getCapacity(), used,
                            Math.max(0, rate.getCapacity() - used), rate.getDayPriceVnd(), rate.getOvernightPriceVnd(),
                            rate.getMonthlyPriceVnd(), rate.getParkAndRideDiscountPercent());
                })
                .toList();
        Station station = lot.getStation();
        boolean open = lot.getStatus() == ParkingLotStatus.ACTIVE
                && ParkingFeeCalculator.isOpen(lot.getOpenTime(), lot.getCloseTime(), now);
        return new LotResponse(lot.getId(), lot.getCode(), lot.getName(), lot.getAddress(), station.getId(),
                station.getCode(), station.getNameStation(), lot.getOpenTime(), lot.getCloseTime(), lot.getStatus(),
                open, vehicles);
    }

    private static SessionResponse toSessionResponse(ParkingSession s, Integer estimated) {
        Integer payable = s.getFeeVnd() == null ? null : s.getFeeVnd() - Optional.ofNullable(s.getDiscountVnd()).orElse(0);
        return new SessionResponse(s.getId(), s.getSessionCode(), s.getLot().getId(), s.getLot().getCode(),
                s.getLot().getName(), s.getVehicleType(), s.getPlateNumber(), s.getStatus(), s.getCheckInAt(),
                s.getCheckOutAt(), s.getMonthlyPass() == null ? null : s.getMonthlyPass().getPassCode(), s.getFeeVnd(),
                s.getDiscountVnd(), payable, s.getPaymentMethod(), estimated);
    }

    private static MonthlyPassResponse toPassResponse(ParkingMonthlyPass p, Instant now) {
        return toPassResponse(p, now, null);
    }

    private static MonthlyPassResponse toPassResponse(ParkingMonthlyPass p, Instant now,
            PaymentOrderService.PaymentInstruction payment) {
        return new MonthlyPassResponse(p.getId(), p.getPassCode(), p.getLot().getId(), p.getLot().getCode(),
                p.getLot().getName(), p.getVehicleType(), p.getPlateNumber(), p.getPriceVnd(), p.getValidFrom(),
                p.getValidUntil(), p.getStatus(), p.isValidAt(now), payment);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Dùng cho controller khi parse path variable. */
    static VehicleType parseVehicleType(String raw) {
        return Arrays.stream(VehicleType.values()).filter(v -> v.name().equalsIgnoreCase(raw)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại xe không hợp lệ: " + raw));
    }
}
