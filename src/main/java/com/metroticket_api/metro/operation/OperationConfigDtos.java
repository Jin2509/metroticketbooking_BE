package com.metroticket_api.metro.operation;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.metroticket_api.metro.common.OperationConfigStatus;

public class OperationConfigDtos {

    /**
     * Người tạo lấy từ JWT, không nhận từ client. {@code lineId} trong body được bỏ qua (dùng path).
     */
    public record CreateOperationConfigRequest(
            Long lineId,
            @NotNull @Positive @Max(200) Integer numberOfTrains,
            @NotNull @Positive @Max(240) Integer headwayMinutes,
            @NotNull @Positive @Max(1440) Integer runningTimeMinutes,
            @PositiveOrZero @Max(240) Integer turnaroundTimeMinutes,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotNull Instant effectiveFrom,
            Instant effectiveTo) {
    }

    /** null = giữ nguyên; {@code clearEffectiveTo = true} để bỏ ngày hết hiệu lực. */
    public record UpdateOperationConfigRequest(
            @Positive @Max(200) Integer numberOfTrains,
            @Positive @Max(240) Integer headwayMinutes,
            @Positive @Max(1440) Integer runningTimeMinutes,
            @PositiveOrZero @Max(240) Integer turnaroundTimeMinutes,
            LocalTime startTime,
            LocalTime endTime,
            Instant effectiveFrom,
            Instant effectiveTo,
            Boolean clearEffectiveTo) {
    }

    public record RejectOperationConfigRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record OperationConfigResponse(
            Long id,
            Long lineId,
            Integer version,
            Integer numberOfTrains,
            Integer headwayMinutes,
            Integer runningTimeMinutes,
            Integer turnaroundTimeMinutes,
            LocalTime startTime,
            LocalTime endTime,
            Instant effectiveFrom,
            Instant effectiveTo,
            OperationConfigStatus status,
            UUID createdBy,
            Instant submittedAt,
            UUID approvedBy,
            Instant approvedAt,
            String rejectionReason,
            Instant createdAt,
            Instant updatedAt) {
        public static OperationConfigResponse of(OperationConfig config) {
            return new OperationConfigResponse(
                    config.getId(),
                    config.getLine() == null ? null : config.getLine().getId(),
                    config.getVersion(),
                    config.getNumberOfTrains(),
                    config.getHeadwayMinutes(),
                    config.getRunningTimeMinutes(),
                    config.getTurnaroundTimeMinutes(),
                    config.getStartTime(),
                    config.getEndTime(),
                    config.getEffectiveFrom(),
                    config.getEffectiveTo(),
                    config.getStatus(),
                    config.getCreatedBy(),
                    config.getSubmittedAt(),
                    config.getApprovedBy(),
                    config.getApprovedAt(),
                    config.getRejectionReason(),
                    config.getCreatedAt(),
                    config.getUpdatedAt());
        }
    }
}
