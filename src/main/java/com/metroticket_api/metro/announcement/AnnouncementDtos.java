package com.metroticket_api.metro.announcement;

import java.time.Instant;
import java.util.UUID;

import com.metroticket_api.metro.common.AnnouncementScope;
import com.metroticket_api.metro.common.AnnouncementSeverity;
import com.metroticket_api.metro.common.AnnouncementStatus;

public class AnnouncementDtos {

    public record CreateAnnouncementRequest(
            Long lineId,
            Long stationId,
            String title,
            String content,
            AnnouncementSeverity severity,
            Instant startsAt,
            Instant endsAt,
            AnnouncementScope scope,
            String targetRole,
            UUID createdBy) {
    }

    public record UpdateAnnouncementRequest(
            Long lineId,
            Long stationId,
            String title,
            String content,
            AnnouncementSeverity severity,
            Instant startsAt,
            Instant endsAt,
            AnnouncementScope scope,
            String targetRole,
            UUID createdBy) {
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
