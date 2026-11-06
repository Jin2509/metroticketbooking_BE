package com.metroticket_api.metro.operation;

import java.time.Instant;
import java.time.LocalTime;
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

import com.metroticket_api.metro.common.OperationConfigStatus;
import com.metroticket_api.metro.line.MetroLine;

@Entity
@Table(name = "operation_config")
public class OperationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_id", nullable = false)
    private MetroLine line;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "number_of_trains", nullable = false)
    private Integer numberOfTrains;

    @Column(name = "headway_minutes", nullable = false)
    private Integer headwayMinutes;

    @Column(name = "running_time_minutes", nullable = false)
    private Integer runningTimeMinutes;

    @Column(name = "turnaround_time_minutes", nullable = false)
    private Integer turnaroundTimeMinutes = 0;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OperationConfigStatus status = OperationConfigStatus.DRAFT;

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

    protected OperationConfig() {
    }

    public OperationConfig(MetroLine line, Integer version, Integer numberOfTrains, Integer headwayMinutes,
            Integer runningTimeMinutes, Integer turnaroundTimeMinutes, LocalTime startTime,
            LocalTime endTime, Instant effectiveFrom, Instant effectiveTo, UUID createdBy,
            OperationConfigStatus status) {
        this.line = line;
        this.version = version;
        this.numberOfTrains = numberOfTrains;
        this.headwayMinutes = headwayMinutes;
        this.runningTimeMinutes = runningTimeMinutes;
        this.turnaroundTimeMinutes = turnaroundTimeMinutes == null ? 0 : turnaroundTimeMinutes;
        this.startTime = startTime;
        this.endTime = endTime;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.createdBy = createdBy;
        this.status = status == null ? OperationConfigStatus.DRAFT : status;
    }

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Integer getNumberOfTrains() {
        return numberOfTrains;
    }

    public void setNumberOfTrains(Integer numberOfTrains) {
        this.numberOfTrains = numberOfTrains;
    }

    public Integer getHeadwayMinutes() {
        return headwayMinutes;
    }

    public void setHeadwayMinutes(Integer headwayMinutes) {
        this.headwayMinutes = headwayMinutes;
    }

    public Integer getRunningTimeMinutes() {
        return runningTimeMinutes;
    }

    public void setRunningTimeMinutes(Integer runningTimeMinutes) {
        this.runningTimeMinutes = runningTimeMinutes;
    }

    public Integer getTurnaroundTimeMinutes() {
        return turnaroundTimeMinutes;
    }

    public void setTurnaroundTimeMinutes(Integer turnaroundTimeMinutes) {
        this.turnaroundTimeMinutes = turnaroundTimeMinutes == null ? 0 : turnaroundTimeMinutes;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(Instant effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(Instant effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public OperationConfigStatus getStatus() {
        return status;
    }

    public void setStatus(OperationConfigStatus status) {
        this.status = status;
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
