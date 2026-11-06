package com.metroticket_api.metro.station;

import java.time.Instant;

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

import com.metroticket_api.metro.common.TransferStatus;
import com.metroticket_api.metro.line.MetroLine;

@Entity
@Table(name = "line_transfer")
public class LineTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_line_id", nullable = false)
    private MetroLine fromLine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_line_id", nullable = false)
    private MetroLine toLine;

    @Column(name = "transfer_time_minutes", nullable = false)
    private Integer transferTimeMinutes = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status = TransferStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LineTransfer() {
    }

    public LineTransfer(Station station, MetroLine fromLine, MetroLine toLine, Integer transferTimeMinutes, TransferStatus status) {
        this.station = station;
        this.fromLine = fromLine;
        this.toLine = toLine;
        this.transferTimeMinutes = transferTimeMinutes == null ? 0 : transferTimeMinutes;
        this.status = status == null ? TransferStatus.ACTIVE : status;
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

    public Station getStation() {
        return station;
    }

    public MetroLine getFromLine() {
        return fromLine;
    }

    public MetroLine getToLine() {
        return toLine;
    }

    public Integer getTransferTimeMinutes() {
        return transferTimeMinutes;
    }

    public void setTransferTimeMinutes(Integer transferTimeMinutes) {
        this.transferTimeMinutes = transferTimeMinutes == null ? 0 : transferTimeMinutes;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public void setStatus(TransferStatus status) {
        this.status = status == null ? TransferStatus.ACTIVE : status;
    }

    public void setStation(Station station) {
        this.station = station;
    }

    public void setFromLine(MetroLine fromLine) {
        this.fromLine = fromLine;
    }

    public void setToLine(MetroLine toLine) {
        this.toLine = toLine;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
