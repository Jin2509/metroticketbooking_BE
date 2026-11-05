package com.metroticket_api.metro.operation;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import com.metroticket_api.metro.common.OperationConfigStatus;

public class OperationConfigDtos {

    public record CreateOperationConfigRequest(
            Long lineId,
            Integer numberOfTrains,
            Integer headwayMinutes,
            Integer runningTimeMinutes,
            Integer turnaroundTimeMinutes,
            LocalTime startTime,
            LocalTime endTime,
            Instant effectiveFrom,
            Instant effectiveTo,
            UUID createdBy) {
    }

    public record UpdateOperationConfigRequest(
            Integer numberOfTrains,
            Integer headwayMinutes,
            Integer runningTimeMinutes,
            Integer turnaroundTimeMinutes,
            LocalTime startTime,
            LocalTime endTime,
            Instant effectiveFrom,
            Instant effectiveTo,
            UUID createdBy) {
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
                    config.getApprovedBy(),
                    config.getApprovedAt(),
                    config.getRejectionReason(),
                    config.getCreatedAt(),
                    config.getUpdatedAt());
        }
    }
}
