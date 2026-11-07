package com.metroticket_api.parking;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.metroticket_api.payment.PaymentMethod;

public class ParkingDtos {

    public record CreateLotRequest(
            @NotNull Long stationId,
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 255) String address,
            @NotNull LocalTime openTime,
            @NotNull LocalTime closeTime,
            ParkingLotStatus status) {
    }

    public record UpdateLotRequest(
            @Size(max = 100) String name,
            @Size(max = 255) String address,
            LocalTime openTime,
            LocalTime closeTime,
            ParkingLotStatus status) {
    }

    public record UpsertRateRequest(
            @NotNull @PositiveOrZero @Max(100_000) Integer capacity,
            @NotNull @PositiveOrZero @Max(10_000_000) Integer dayPriceVnd,
            @NotNull @PositiveOrZero @Max(10_000_000) Integer overnightPriceVnd,
            @NotNull @PositiveOrZero @Max(100_000_000) Integer monthlyPriceVnd,
            @PositiveOrZero @Max(100) Integer parkAndRideDiscountPercent) {
    }

    public record RateAvailability(VehicleType vehicleType, int capacity, long occupied, long available,
            int dayPriceVnd, int overnightPriceVnd, int monthlyPriceVnd, int parkAndRideDiscountPercent) {
    }

    public record LotResponse(Long id, String code, String name, String address, Long stationId, String stationCode,
            String stationName, LocalTime openTime, LocalTime closeTime, ParkingLotStatus status, boolean openNow,
            List<RateAvailability> vehicles) {
    }

    public record CheckInRequest(
            @NotNull Long lotId,
            @NotNull VehicleType vehicleType,
            @Size(max = 20) String plateNumber,
            @Size(max = 20) String monthlyPassCode) {
    }

    public record CheckOutRequest(
            @NotBlank @Size(max = 20) String sessionCode,
            /** Bắt buộc với xe có biển số: đối chiếu chống lấy nhầm/trộm xe. */
            @Size(max = 20) String plateNumber,
            PaymentMethod paymentMethod,
            /** QR vé Metro để hưởng ưu đãi Park &amp; Ride (tuỳ chọn). */
            @Size(max = 64) String metroTicketQrToken) {
    }

    public record SessionResponse(UUID id, String sessionCode, Long lotId, String lotCode, String lotName,
            VehicleType vehicleType, String plateNumber, ParkingSessionStatus status, Instant checkInAt,
            Instant checkOutAt, String monthlyPassCode, Integer feeVnd, Integer discountVnd, Integer payableVnd,
            PaymentMethod paymentMethod, Integer estimatedFeeVnd) {
    }

    public record BuyMonthlyPassRequest(
            @NotNull Long lotId,
            @NotNull VehicleType vehicleType,
            @NotBlank @Size(max = 20) String plateNumber,
            @NotNull PaymentMethod paymentMethod) {
    }

    public record MonthlyPassResponse(UUID id, String passCode, Long lotId, String lotCode, String lotName,
            VehicleType vehicleType, String plateNumber, int priceVnd, Instant validFrom, Instant validUntil,
            MonthlyPassStatus status, boolean validNow,
            com.metroticket_api.payment.PaymentOrderService.PaymentInstruction payment) {
    }
}
