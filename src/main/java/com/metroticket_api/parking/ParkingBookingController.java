package com.metroticket_api.parking;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.payment.PaymentMethod;
import com.metroticket_api.payment.PaymentOrderService.PaymentInstruction;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Parking bookings", description = "UC14, UC17: đặt chỗ gửi xe và xác nhận bằng QR")
public class ParkingBookingController {

    public record CreateBookingRequest(@NotNull Long lotId, @NotNull VehicleType vehicleType,
            @Size(max = 20) String plateNumber, @NotNull Instant startAt, @NotNull Instant endAt,
            @NotNull PaymentMethod paymentMethod) {
    }

    public record ScanRequest(@NotBlank @Size(max = 64) String qrToken) {
    }

    /** qrToken chỉ có khi lượt đã thanh toán; payment có khi lượt còn chờ thanh toán. */
    public record BookingResponse(UUID id, String bookingCode, String qrToken, Long lotId, String lotName,
            VehicleType vehicleType, String plateNumber, int priceVnd, int overtimeFeeVnd, Instant startAt, Instant endAt,
            Instant checkinAt, Instant checkoutAt, ParkingBookingStatus status, PaymentInstruction payment) {
        static BookingResponse of(ParkingBooking b, boolean includeQr, PaymentInstruction payment) {
            boolean paid = b.getStatus() == ParkingBookingStatus.PAID || b.getStatus() == ParkingBookingStatus.CHECKED_IN;
            return new BookingResponse(b.getId(), b.getBookingCode(), includeQr && paid ? b.getQrToken() : null,
                    b.getLot().getId(), b.getLot().getName(), b.getVehicleType(), b.getPlateNumber(), b.getPriceVnd(),
                    b.getOvertimeFeeVnd(), b.getStartAt(), b.getEndAt(), b.getCheckinAt(), b.getCheckoutAt(), b.getStatus(), payment);
        }
    }

    /** overtimeFeeVnd > 0: nhân viên thu thêm khoản này tại bãi. */
    public record ScanResponse(String bookingCode, String lotName, VehicleType vehicleType, String plateNumber,
            ParkingBookingStatus status, Instant startAt, Instant endAt, int overtimeFeeVnd) {
    }

    private final ParkingBookingService service;

    public ParkingBookingController(ParkingBookingService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority('PARKING_BUY')")
    @PostMapping("/parking/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@Valid @RequestBody CreateBookingRequest request) {
        return service.create(request);
    }

    @PreAuthorize("hasAuthority('PARKING_BUY')")
    @GetMapping("/me/parking-bookings")
    public Page<BookingResponse> mine(@PageableDefault(size = 20) Pageable pageable) {
        return service.mine(pageable);
    }

    @PreAuthorize("hasAnyAuthority('PARKING_OPERATE', 'PARKING_MANAGE')")
    @PostMapping("/parking/bookings/scan")
    public ScanResponse scan(@Valid @RequestBody ScanRequest request) {
        return service.scan(request.qrToken());
    }
}
