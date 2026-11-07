package com.metroticket_api.ticket;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.metroticket_api.metro.station.Station;
import com.metroticket_api.payment.PaymentMethod;
import com.metroticket_api.payment.PaymentOrderService.PaymentInstruction;

public class TicketDtos {

    /**
     * periods: số kỳ mua (mặc định 1). Vé tháng được mua tối đa {@code maxPeriods} tháng một lần.
     * studentDiscount: null = tự áp ưu đãi sinh viên nếu đủ điều kiện; false = mua giá thường.
     */
    public record PurchaseTicketRequest(
            @NotBlank @Size(max = 30) String productCode,
            @Min(1) @Max(12) Integer periods,
            @NotNull PaymentMethod paymentMethod,
            Boolean studentDiscount) {
        public PurchaseTicketRequest(String productCode, Integer periods, PaymentMethod paymentMethod) {
            this(productCode, periods, paymentMethod, null);
        }
    }

    public record StationRef(Long id, String code, String name) {
        static StationRef of(Station s) {
            return s == null ? null : new StationRef(s.getId(), s.getCode(), s.getNameStation());
        }
    }

    /**
     * qrToken chỉ trả cho chính chủ vé và chỉ khi vé đã thanh toán. validUntil: trước khi kích hoạt là hạn chót
     * quẹt vào lần đầu, sau khi kích hoạt là hạn dùng. payment: hướng dẫn chuyển khoản nếu vé đang chờ thanh toán.
     */
    public record TicketResponse(UUID id, String ticketCode, String qrToken, UUID userId, String productCode,
            String productName, DurationUnit durationUnit, int durationValue, int periods, int listUnitPriceVnd,
            String discountCode, int discountPercent, int unitPriceVnd, int priceVnd, TicketStatus status, Instant purchasedAt, Instant validFrom, Instant validUntil,
            Instant activatedAt, StationRef lastEntryStation, Instant lastEntryAt, int tripsCount, boolean refundable,
            Instant refundedAt, PaymentInstruction payment) {
        static TicketResponse of(Ticket t, boolean includeQr, boolean refundable) {
            return of(t, includeQr, refundable, null);
        }

        static TicketResponse of(Ticket t, boolean includeQr, boolean refundable, PaymentInstruction payment) {
            boolean qr = includeQr && t.getStatus().hasUsableQr();
            return new TicketResponse(t.getId(), t.getTicketCode(), qr ? t.getQrToken() : null, t.getUserId(),
                    t.getProduct().getCode(), t.getProduct().getName(), t.getDurationUnit(), t.getDurationValue(),
                    t.getPeriods(), t.getListUnitPriceVnd(), t.getDiscountCode(), t.getDiscountPercent(),
                    t.getUnitPriceVnd(), t.getPriceVnd(), t.getStatus(), t.getPurchasedAt(),
                    t.getValidFrom(), t.getValidUntil(), t.getActivatedAt(), StationRef.of(t.getLastEntryStation()),
                    t.getLastEntryAt(), t.getTripsCount(), refundable, t.getRefundedAt(), payment);
        }
    }

    /** awaitingManualTransfer = true: vé trả bằng chuyển khoản, kế toán sẽ chuyển trả tiền (không tự động). */
    public record RefundResponse(UUID ticketId, TicketStatus status, int refundedAmountVnd,
            boolean awaitingManualTransfer, Instant refundedAt) {
    }

    public record ValidationResponse(Long id, StationRef station, GateDirection direction, GateResult result,
            String reason, UUID validatedBy, Instant validatedAt) {
        static ValidationResponse of(TicketValidation v) {
            return new ValidationResponse(v.getId(), StationRef.of(v.getStation()), v.getDirection(), v.getResult(),
                    v.getReason(), v.getValidatedBy(), v.getValidatedAt());
        }
    }

    public record GateValidationRequest(
            @NotBlank @Size(max = 64) String qrToken,
            @NotNull Long stationId,
            @NotNull GateDirection direction) {
    }

    public record GateValidationResponse(GateResult result, String reason, GateDirection direction,
            StationRef station, String ticketCode, String productCode, TicketStatus ticketStatus,
            Instant validUntil, Instant validatedAt) {
    }
}
