package com.metroticket_api.ticket;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class TicketProductDtos {

    /**
     * Vé theo thời gian: bắt buộc durationUnit, durationValue, priceVnd.
     * Vé lượt (singleJourney = true): bắt buộc farePerKmVnd (đơn giá/km, lưu thành tham số FARE_PER_KM_&lt;mã&gt;);
     * thời lượng cố định 1 ngày, 1 kỳ; các trường thời lượng/giá/số kỳ bị bỏ qua.
     */
    public record CreateProductRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{2,30}$", message = "Mã chỉ gồm chữ, số, gạch dưới (2–30 ký tự)") String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 2000) String description,
            DurationUnit durationUnit,
            @Min(1) @Max(12) Integer durationValue,
            @Positive @Max(100_000_000) Integer priceVnd,
            @Min(1) @Max(12) Integer maxPeriods,
            @Min(1) @Max(90) Integer activationWindowDays,
            ProductStatus status,
            Boolean singleJourney,
            @Min(1) @Max(1_000_000) Integer farePerKmVnd) {
        public CreateProductRequest(String code, String name, String description, DurationUnit durationUnit,
                Integer durationValue, Integer priceVnd, Integer maxPeriods, Integer activationWindowDays,
                ProductStatus status) {
            this(code, name, description, durationUnit, durationValue, priceVnd, maxPeriods, activationWindowDays,
                    status, null, null);
        }
    }

    /**
     * null = giữ nguyên. Mã loại vé không đổi được. {@code version} bắt buộc: lấy từ lần GET gần nhất, lệch thì 409
     * (2 người cùng sửa, người sau phải tải lại để không ghi đè giá của người trước).
     * Trạng thái đổi qua /activate, /deactivate.
     */
    public record UpdateProductRequest(
            @NotNull Long version,
            @Size(max = 100) String name,
            @Size(max = 2000) String description,
            DurationUnit durationUnit,
            @Min(1) @Max(12) Integer durationValue,
            @Positive @Max(100_000_000) Integer priceVnd,
            @Min(1) @Max(12) Integer maxPeriods,
            @Min(1) @Max(90) Integer activationWindowDays) {
    }

    /** Thông tin công khai cho app (không lộ người tạo/sửa). */
    /** Vé lượt: priceVnd là giá tối thiểu, farePerKmVnd là đơn giá/km đang áp dụng (null với vé theo thời gian). */
    public record PublicProductResponse(String code, String name, String description, DurationUnit durationUnit,
            int durationValue, int priceVnd, int maxPeriods, int activationWindowDays, boolean singleJourney,
            Integer farePerKmVnd) {
        static PublicProductResponse of(TicketProduct p, Integer farePerKmVnd) {
            return new PublicProductResponse(p.getCode(), p.getName(), p.getDescription(), p.getDurationUnit(),
                    p.getDurationValue(), p.getPriceVnd(), p.getMaxPeriods(), p.getActivationWindowDays(), p.isSingleJourney(),
                    farePerKmVnd);
        }
    }

    public record ProductResponse(Long id, String code, String name, String description, DurationUnit durationUnit,
            int durationValue, int priceVnd, int maxPeriods, int activationWindowDays, ProductStatus status,
            boolean hasSoldTickets, UUID createdBy, UUID updatedBy, long version, Instant createdAt, Instant updatedAt,
            boolean singleJourney) {
        static ProductResponse of(TicketProduct p, boolean sold) {
            return new ProductResponse(p.getId(), p.getCode(), p.getName(), p.getDescription(), p.getDurationUnit(),
                    p.getDurationValue(), p.getPriceVnd(), p.getMaxPeriods(), p.getActivationWindowDays(), p.getStatus(),
                    sold, p.getCreatedBy(), p.getUpdatedBy(), p.getVersion(), p.getCreatedAt(), p.getUpdatedAt(),
                    p.isSingleJourney());
        }
    }

    public record AuditResponse(Long id, TicketProductAudit.Action action, UUID changedBy, String changes,
            Instant changedAt) {
        static AuditResponse of(TicketProductAudit a) {
            return new AuditResponse(a.getId(), a.getAction(), a.getChangedBy(), a.getChanges(), a.getChangedAt());
        }
    }
}
