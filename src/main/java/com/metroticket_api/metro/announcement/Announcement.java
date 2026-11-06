package com.metroticket_api.metro.announcement;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import com.metroticket_api.metro.common.AnnouncementScope;
import com.metroticket_api.metro.common.AnnouncementSeverity;
import com.metroticket_api.metro.common.AnnouncementStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.station.Station;

@Entity
@Table(name = "announcement")
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_id")
    private MetroLine line;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id")
    private Station station;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnnouncementSeverity severity = AnnouncementSeverity.INFO;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AnnouncementStatus status = AnnouncementStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnnouncementScope scope = AnnouncementScope.ALL;

    @Column(name = "target_role", length = 50)
    private String targetRole;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Announcement() {
    }

    public Announcement(String title, String content, AnnouncementSeverity severity,
            Instant startsAt, Instant endsAt, AnnouncementStatus status,
            AnnouncementScope scope, String targetRole, UUID createdBy) {
        this.title = title;
        this.content = content;
        this.severity = severity == null ? AnnouncementSeverity.INFO : severity;
        this.startsAt = startsAt == null ? Instant.now() : startsAt;
        this.endsAt = endsAt;
        this.status = status == null ? AnnouncementStatus.DRAFT : status;
        this.scope = scope == null ? AnnouncementScope.ALL : scope;
        this.targetRole = targetRole;
        this.createdBy = createdBy;
    }

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.startsAt = startsAt == null ? now : startsAt;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public MetroLine getLine() {
        return line;
    }

    public void setLine(MetroLine line) {
        this.line = line;
    }

    public Station getStation() {
        return station;
    }

    public void setStation(Station station) {
        this.station = station;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public AnnouncementSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(AnnouncementSeverity severity) {
        this.severity = severity;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(Instant startsAt) {
        this.startsAt = startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(Instant endsAt) {
        this.endsAt = endsAt;
    }

    public AnnouncementStatus getStatus() {
        return status;
    }

    public void setStatus(AnnouncementStatus status) {
        this.status = status;
    }

    public AnnouncementScope getScope() {
        return scope;
    }

    public void setScope(AnnouncementScope scope) {
        this.scope = scope == null ? AnnouncementScope.ALL : scope;
    }

    public String getTargetRole() {
        return targetRole;
    }

    public void setTargetRole(String targetRole) {
        this.targetRole = targetRole;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(UUID approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
