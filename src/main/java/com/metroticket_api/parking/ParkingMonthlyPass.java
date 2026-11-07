package com.metroticket_api.parking;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import com.metroticket_api.ticket.SecureCodes;

@Entity
@Table(name = "parking_monthly_pass")
public class ParkingMonthlyPass {

    @Id
    private UUID id;

    @Column(name = "pass_code", nullable = false, unique = true, length = 20)
    private String passCode;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lot_id", nullable = false)
    private ParkingLot lot;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Column(name = "plate_number", nullable = false, length = 20)
    private String plateNumber;

    @Column(name = "price_vnd", nullable = false)
    private int priceVnd;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MonthlyPassStatus status = MonthlyPassStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ParkingMonthlyPass() {
    }

    public ParkingMonthlyPass(UUID userId, ParkingLot lot, VehicleType vehicleType, String plateNumber, int priceVnd,
            Instant validFrom, Instant validUntil) {
        this.id = UUID.randomUUID();
        this.passCode = SecureCodes.humanCode("PM", 8);
        this.userId = userId;
        this.lot = lot;
        this.vehicleType = vehicleType;
        this.plateNumber = plateNumber;
        this.priceVnd = priceVnd;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public void awaitPayment() {
        status = MonthlyPassStatus.PENDING_PAYMENT;
    }

    /** Thời hạn vé tháng tính từ lúc tiền về, không phải lúc đặt. */
    public void activate(Instant from, Instant until) {
        status = MonthlyPassStatus.ACTIVE;
        validFrom = from;
        validUntil = until;
    }

    public void cancel() {
        status = MonthlyPassStatus.CANCELLED;
    }

    public boolean isValidAt(Instant at) {
        return status == MonthlyPassStatus.ACTIVE && !at.isBefore(validFrom) && at.isBefore(validUntil);
    }

    public UUID getId() { return id; }
    public String getPassCode() { return passCode; }
    public UUID getUserId() { return userId; }
    public ParkingLot getLot() { return lot; }
    public VehicleType getVehicleType() { return vehicleType; }
    public String getPlateNumber() { return plateNumber; }
    public int getPriceVnd() { return priceVnd; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidUntil() { return validUntil; }
    public MonthlyPassStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
