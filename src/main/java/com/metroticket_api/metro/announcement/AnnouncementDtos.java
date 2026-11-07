package com.metroticket_api.metro.announcement;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.metroticket_api.metro.common.AnnouncementScope;
import com.metroticket_api.metro.common.AnnouncementSeverity;
import com.metroticket_api.metro.common.AnnouncementStatus;

public class AnnouncementDtos {

    /** Người tạo lấy từ JWT. */
    public record CreateAnnouncementRequest(
            Long lineId,
            Long stationId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 10_000) String content,
            AnnouncementSeverity severity,
            Instant startsAt,
            Instant endsAt,
            AnnouncementScope scope,
            @Size(max = 50) String targetRole) {
    }

    public record UpdateAnnouncementRequest(
            Long lineId,
            Long stationId,
            @Size(max = 200) String title,
            @Size(max = 10_000) String content,
            AnnouncementSeverity severity,
            Instant startsAt,
            Instant endsAt,
            AnnouncementScope scope,
            @Size(max = 50) String targetRole) {
    }

    public record RejectAnnouncementRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record AnnouncementResponse(
            Long id,
            Long lineId,
            Long stationId,
            String title,
            String content,
            AnnouncementSeverity severity,
            Instant startsAt,
            Instant endsAt,
            AnnouncementStatus status,
            AnnouncementScope scope,
            String targetRole,
            UUID createdBy,
            UUID approvedBy,
            Instant approvedAt,
            String rejectionReason,
            Instant createdAt,
            Instant updatedAt) {
        public static AnnouncementResponse of(Announcement announcement) {
            return new AnnouncementResponse(
                    announcement.getId(),
                    announcement.getLine() == null ? null : announcement.getLine().getId(),
                    announcement.getStation() == null ? null : announcement.getStation().getId(),
                    announcement.getTitle(),
                    announcement.getContent(),
                    announcement.getSeverity(),
                    announcement.getStartsAt(),
                    announcement.getEndsAt(),
                    announcement.getStatus(),
                    announcement.getScope(),
                    announcement.getTargetRole(),
                    announcement.getCreatedBy(),
                    announcement.getApprovedBy(),
                    announcement.getApprovedAt(),
                    announcement.getRejectionReason(),
                    announcement.getCreatedAt(),
                    announcement.getUpdatedAt());
        }
    }
}
