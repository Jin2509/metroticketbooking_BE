package com.metroticket_api.parking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.audit.AuditLogService;
import com.metroticket_api.metro.staff.StaffStationService;
import com.metroticket_api.parking.ParkingBookingController.BookingResponse;
import com.metroticket_api.parking.ParkingBookingController.CreateBookingRequest;
import com.metroticket_api.parking.ParkingBookingController.ScanResponse;
import com.metroticket_api.payment.PaymentMethod;
import com.metroticket_api.payment.PaymentOrder;
import com.metroticket_api.payment.PaymentOrderService;
import com.metroticket_api.payment.PaymentProps;
import com.metroticket_api.payment.PaymentPurpose;
import com.metroticket_api.payment.PaymentService;
import com.metroticket_api.rbac.PermissionCodes;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.system.SystemParamKey;
import com.metroticket_api.system.SystemParamService;

/**
 * Đặt chỗ gửi xe trả trước (UC14) và xác nhận gửi, lấy xe bằng QR (UC17). Phí tính theo lượt ngày và số đêm giống gửi xe
 * tại chỗ; lấy xe sau giờ đã đăng ký thì thu thêm phí quá giờ theo tham số hệ thống, làm tròn lên theo giờ.
 */
@Service
public class ParkingBookingService {

    static final Duration MAX_DURATION = Duration.ofDays(30);

    private final ParkingBookingRepository bookings;
    private final ParkingLotRepository lots;
    private final ParkingRateRepository rates;
    private final ParkingSessionRepository sessions;
    private final StaffStationService staffStations;
    private final PaymentOrderService paymentOrders;
    private final PaymentService payments;
    private final PaymentProps paymentProps;
    private final SystemParamService params;
    private final AuditLogService audit;
    private final Clock clock;

    public ParkingBookingService(ParkingBookingRepository bookings, ParkingLotRepository lots, ParkingRateRepository rates,
            ParkingSessionRepository sessions, StaffStationService staffStations, PaymentOrderService paymentOrders,
            PaymentService payments, PaymentProps paymentProps, SystemParamService params, AuditLogService audit, Clock clock) {
        this.bookings = bookings;
        this.lots = lots;
        this.rates = rates;
        this.sessions = sessions;
        this.staffStations = staffStations;
        this.paymentOrders = paymentOrders;
        this.payments = payments;
        this.paymentProps = paymentProps;
        this.params = params;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public BookingResponse create(CreateBookingRequest request) {
        UUID userId = CurrentUser.id();
        PaymentMethod method = request.paymentMethod();
        boolean qr = method == PaymentMethod.SEPAY_QR;
        if (!qr && !(method.isMockOnline() && paymentProps.mockEnabled())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Đặt chỗ gửi xe chỉ hỗ trợ thanh toán SEPAY_QR");
        }
        Instant now = clock.instant();
        Instant start = request.startAt();
        Instant end = request.endAt();
        if (!end.isAfter(start) || end.isBefore(now) || Duration.between(start, end).compareTo(MAX_DURATION) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thời gian gửi xe không hợp lệ (tối đa 30 ngày)");
        }
        ParkingLot lot = lots.findById(request.lotId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bãi xe"));
        if (lot.getStatus() != ParkingLotStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bãi xe đang " + lot.getStatus() + ", không nhận đặt chỗ");
        }
        String plate;
        try {
            plate = PlateNumbers.require(request.plateNumber(), request.vehicleType());
        } catch (ResponseStatusException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Biển số xe đã nhập không hợp lệ");
        }
        // Khoá dòng sức chứa để hai người không cùng lấy chỗ cuối
        ParkingRate rate = rates.findForUpdate(lot.getId(), request.vehicleType())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Bãi không nhận loại xe " + request.vehicleType()));
        if (plate != null && bookings.existsOpenForPlate(plate, now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lượt đặt chỗ giữ xe trước đó vẫn còn hiệu lực");
        }
        long parked = sessions.countByLotIdAndVehicleTypeAndStatus(lot.getId(), request.vehicleType(), ParkingSessionStatus.PARKED);
        if (parked + bookings.countHolding(lot.getId(), request.vehicleType(), start, end) >= rate.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bãi đã hết chỗ cho " + request.vehicleType());
        }
        int price = ParkingFeeCalculator.fee(rate.getDayPriceVnd(), rate.getOvernightPriceVnd(), start, end);
        ParkingBooking booking = new ParkingBooking(userId, lot, request.vehicleType(), plate, price, start, end);
        if (price == 0) {
            booking.markPaid();
            bookings.save(booking);
            return BookingResponse.of(booking, true, null);
        }
        bookings.save(booking);
        if (qr) {
            PaymentOrder order = paymentOrders.createOrder(userId, PaymentPurpose.PARKING_BOOKING, booking.getId().toString(), price);
            return BookingResponse.of(booking, true, paymentOrders.instructionOf(order));
        }
        booking.markPaid();
        payments.charge(userId, PaymentPurpose.PARKING_BOOKING, booking.getId().toString(), price, method);
        return BookingResponse.of(booking, true, null);
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> mine(Pageable pageable) {
        return bookings.findMine(CurrentUser.id(), pageable).map(b -> BookingResponse.of(b, true,
                b.getStatus() == ParkingBookingStatus.PENDING_PAYMENT
                        ? paymentOrders.instructionFor(PaymentPurpose.PARKING_BOOKING, b.getId().toString()).orElse(null)
                        : null));
    }

    /** Quét lần đầu là xe vào, quét lần sau là xe ra. */
    @Transactional
    public ScanResponse scan(String qrToken) {
        ParkingBooking booking = bookings.findByQrTokenForUpdate(qrToken.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lượt giữ xe không tồn tại"));
        staffStations.requireOnDutyAt(booking.getLot().getStation().getId(), PermissionCodes.PARKING_MANAGE);
        Instant now = clock.instant();
        UUID operator = CurrentUser.id();
        int overtime = 0;
        switch (booking.getStatus()) {
            case PAID -> {
                if (now.isAfter(booking.getEndAt())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Đăng ký gửi xe đã quá hạn");
                }
                booking.checkIn(now, operator);
            }
            case CHECKED_IN -> {
                overtime = overtimeFee(booking.getEndAt(), now, params.intValue(SystemParamKey.PARKING_OVERTIME_FEE_PER_HOUR));
                booking.complete(now, operator, overtime);
                if (overtime > 0) {
                    // Nhân viên thu tiền mặt tại bãi nên ghi nhận luôn để báo cáo doanh thu khớp
                    payments.charge(booking.getUserId(), PaymentPurpose.PARKING_BOOKING, booking.getId().toString(), overtime,
                            PaymentMethod.CASH);
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Lượt giữ xe đang ở trạng thái " + booking.getStatus());
        }
        audit.log("PARKING_BOOKING_SCAN", "parking_booking", booking.getId(), "Trạng thái mới: " + booking.getStatus());
        return new ScanResponse(booking.getBookingCode(), booking.getLot().getName(), booking.getVehicleType(),
                booking.getPlateNumber(), booking.getStatus(), booking.getStartAt(), booking.getEndAt(), overtime);
    }

    static int overtimeFee(Instant endAt, Instant now, int feePerHour) {
        if (!now.isAfter(endAt)) {
            return 0;
        }
        long seconds = Duration.between(endAt, now).toSeconds();
        long hours = (seconds + 3599) / 3600;
        return Math.toIntExact(hours * feePerHour);
    }
}
