package com.metroticket_api.metro.staff;

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

import com.metroticket_api.metro.common.TransferStatus;
import com.metroticket_api.metro.station.Station;

/** Phân công nhân viên làm việc tại ga (bảng staff_station, V10). Trạng thái ACTIVE/INACTIVE. */
@Entity
@Table(name = "staff_station")
public class StaffStation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    /** Dùng lại enum ACTIVE/INACTIVE (cùng tập giá trị với CHECK của bảng). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status = TransferStatus.ACTIVE;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    protected StaffStation() {
    }

    public StaffStation(UUID userId, Station station, Instant assignedAt) {
        this.userId = userId;
        this.station = station;
        this.assignedAt = assignedAt;
        this.status = TransferStatus.ACTIVE;
    }

    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
    public Station getStation() { return station; }
    public TransferStatus getStatus() { return status; }
    public Instant getAssignedAt() { return assignedAt; }

    public void deactivate() {
        this.status = TransferStatus.INACTIVE;
    }
}
