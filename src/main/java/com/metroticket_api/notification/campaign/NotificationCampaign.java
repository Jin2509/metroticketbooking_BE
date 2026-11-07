package com.metroticket_api.notification.campaign;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.metroticket_api.notification.NotificationPriority;
import com.metroticket_api.notification.NotificationType;

@Entity
@Table(name = "notification_campaign")
public class NotificationCampaign {

    public enum Source { ADMIN, ANNOUNCEMENT }

    @Id private UUID id;
    @Column(nullable = false, length = 200) private String title;
    @Column(nullable = false, length = 2000) private String body;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private NotificationType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private NotificationPriority priority;
    @Column(length = 300) private String link;
    @Enumerated(EnumType.STRING) @Column(name = "audience_type", nullable = false, length = 30) private AudienceType audienceType;
    @Column(name = "audience_value", length = 50) private String audienceValue;
    @Column(name = "channel_push", nullable = false) private boolean channelPush;
    @Column(name = "channel_email", nullable = false) private boolean channelEmail;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private CampaignStatus status;
    @Column(name = "scheduled_at") private Instant scheduledAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "recipient_count", nullable = false) private int recipientCount;
    @Column(name = "push_queued", nullable = false) private int pushQueued;
    @Column(name = "email_queued", nullable = false) private int emailQueued;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Source source = Source.ADMIN;
    @Column(name = "announcement_id") private Long announcementId;
    @Column(length = 500) private String error;
    @Column(name = "created_by", updatable = false) private UUID createdBy;
    @Column(name = "updated_by") private UUID updatedBy;
    @Column(name = "sent_by") private UUID sentBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;

    protected NotificationCampaign() {
    }

    public NotificationCampaign(String title, String body, NotificationType type, NotificationPriority priority,
            String link, AudienceType audienceType, String audienceValue, boolean push, boolean email, UUID createdBy,
            Instant now) {
        this.id = UUID.randomUUID();
        this.status = CampaignStatus.DRAFT;
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
        edit(title, body, type, priority, link, audienceType, audienceValue, push, email, createdBy, now);
    }

    public void edit(String title, String body, NotificationType type, NotificationPriority priority, String link,
            AudienceType audienceType, String audienceValue, boolean push, boolean email, UUID by, Instant now) {
        this.title = title;
        this.body = body;
        this.type = type;
        this.priority = priority == null ? NotificationPriority.NORMAL : priority;
        this.link = link;
        this.audienceType = audienceType;
        this.audienceValue = audienceValue;
        this.channelPush = push;
        this.channelEmail = email;
        this.updatedBy = by;
        this.updatedAt = now;
    }

    public void fromAnnouncement(Long announcementId) {
        this.source = Source.ANNOUNCEMENT;
        this.announcementId = announcementId;
    }

    public void schedule(Instant at, UUID by, Instant now) {
        status = CampaignStatus.SCHEDULED;
        scheduledAt = at;
        sentBy = by;
        updatedAt = now;
    }

    public void unschedule(UUID by, Instant now) {
        status = CampaignStatus.DRAFT;
        scheduledAt = null;
        updatedBy = by;
        updatedAt = now;
    }

    public void cancel(UUID by, Instant now) {
        status = CampaignStatus.CANCELLED;
        updatedBy = by;
        updatedAt = now;
    }

    public void start(Instant now) {
        status = CampaignStatus.SENDING;
        startedAt = now;
        updatedAt = now;
    }

    public void complete(int recipients, int push, int email, Instant now) {
        status = CampaignStatus.SENT;
        recipientCount = recipients;
        pushQueued = push;
        emailQueued = email;
        completedAt = now;
        updatedAt = now;
    }

    public void fail(String error, Instant now) {
        status = CampaignStatus.FAILED;
        this.error = error == null || error.length() <= 500 ? error : error.substring(0, 500);
        completedAt = now;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public NotificationType getType() { return type; }
    public NotificationPriority getPriority() { return priority; }
    public String getLink() { return link; }
    public AudienceType getAudienceType() { return audienceType; }
    public String getAudienceValue() { return audienceValue; }
    public boolean isChannelPush() { return channelPush; }
    public boolean isChannelEmail() { return channelEmail; }
    public CampaignStatus getStatus() { return status; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public int getRecipientCount() { return recipientCount; }
    public int getPushQueued() { return pushQueued; }
    public int getEmailQueued() { return emailQueued; }
    public Source getSource() { return source; }
    public Long getAnnouncementId() { return announcementId; }
    public String getError() { return error; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
    public UUID getSentBy() { return sentBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
