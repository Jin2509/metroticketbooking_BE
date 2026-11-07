package com.metroticket_api.notification.campaign;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.metroticket_api.notification.NotificationPriority;
import com.metroticket_api.notification.NotificationType;

public class CampaignDtos {

    static final String LINK = "^/[A-Za-z0-9/_\\-?=&.%]*$";

    /**
     * type: ANNOUNCEMENT | PROMOTION | SYSTEM. link: màn hình trong app (bắt đầu bằng "/"), không nhận URL ngoài.
     * audienceValue: mã vai trò (ROLE), lineId (LINE), stationId (STATION). userIds: khi audienceType = USERS.
     */
    public record CampaignRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 2000) String body,
            @NotNull NotificationType type,
            NotificationPriority priority,
            @Size(max = 300) @Pattern(regexp = LINK, message = "link phải là đường dẫn trong app, bắt đầu bằng /") String link,
            @NotNull AudienceType audienceType,
            @Size(max = 50) String audienceValue,
            @Size(max = 5000) Set<UUID> userIds,
            Boolean push,
            Boolean email) {
    }

    public record UpdateCampaignRequest(@NotNull Long version, @NotNull CampaignRequest campaign) {
    }

    /** scheduledAt null = gửi ngay. */
    public record SendRequest(@NotNull Long version, Instant scheduledAt) {
    }

    public record VersionRequest(@NotNull Long version) {
    }

    public record CampaignResponse(UUID id, String title, String body, NotificationType type,
            NotificationPriority priority, String link, AudienceType audienceType, String audienceValue,
            boolean push, boolean email, CampaignStatus status, Instant scheduledAt, Instant startedAt,
            Instant completedAt, int recipientCount, int pushQueued, int emailQueued,
            NotificationCampaign.Source source, Long announcementId, String error, UUID createdBy, UUID updatedBy,
            UUID sentBy, Instant createdAt, Instant updatedAt, long version) {
        static CampaignResponse of(NotificationCampaign c) {
            return new CampaignResponse(c.getId(), c.getTitle(), c.getBody(), c.getType(), c.getPriority(), c.getLink(),
                    c.getAudienceType(), c.getAudienceValue(), c.isChannelPush(), c.isChannelEmail(), c.getStatus(),
                    c.getScheduledAt(), c.getStartedAt(), c.getCompletedAt(), c.getRecipientCount(), c.getPushQueued(),
                    c.getEmailQueued(), c.getSource(), c.getAnnouncementId(), c.getError(), c.getCreatedBy(),
                    c.getUpdatedBy(), c.getSentBy(), c.getCreatedAt(), c.getUpdatedAt(), c.getVersion());
        }
    }

    public record PreviewResponse(int recipients, boolean emailAllowed, int maxEmailRecipients) {
    }

    /** read: số người đã mở; deliveries: {PUSH: {SENT: n, FAILED: m, PENDING: k}, EMAIL: {...}}. */
    public record StatsResponse(UUID campaignId, CampaignStatus status, long recipients, long read,
            Map<String, Map<String, Long>> deliveries) {
    }
}
