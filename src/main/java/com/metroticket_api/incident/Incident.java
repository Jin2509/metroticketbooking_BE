package com.metroticket_api.incident;

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
import jakarta.persistence.Table;

import com.metroticket_api.metro.station.Station;

@Entity
@Table(name = "incident")
public class Incident {

    public enum Type { EQUIPMENT, SECURITY, MEDICAL, PASSENGER, OTHER }

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    public enum Status { NEW, IN_PROGRESS, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @Column(name = "reported_by")
    private UUID reportedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "incident_type", nullable = false, length = 30)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "image_key", length = 500)
    private String imageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "handled_by")
    private UUID handledBy;

    @Column(columnDefinition = "TEXT")
    private String resolution;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Incident() {
    }

    Incident(Station station, UUID reportedBy, Type type, Severity severity, String description, String imageKey, Instant createdAt) {
        this.station = station;
        this.reportedBy = reportedBy;
        this.type = type;
        this.severity = severity;
        this.description = description;
        this.imageKey = imageKey;
        this.createdAt = createdAt;
        this.status = Status.NEW;
    }

    void accept(UUID by, Instant at) {
        status = Status.IN_PROGRESS;
        handledBy = by;
        acceptedAt = at;
    }

    void resolve(String result, Instant at) {
        status = Status.RESOLVED;
        resolution = result;
        resolvedAt = at;
    }

    public Long getId() { return id; }
    public Station getStation() { return station; }
    public UUID getReportedBy() { return reportedBy; }
    public Type getType() { return type; }
    public Severity getSeverity() { return severity; }
    public String getDescription() { return description; }
    public String getImageKey() { return imageKey; }
    public Status getStatus() { return status; }
    public UUID getHandledBy() { return handledBy; }
    public String getResolution() { return resolution; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
