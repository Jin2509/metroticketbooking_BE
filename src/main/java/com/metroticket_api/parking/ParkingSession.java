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

import com.metroticket_api.payment.PaymentMethod;
import com.metroticket_api.ticket.SecureCodes;
import com.metroticket_api.ticket.Ticket;

/** Một lượt gửi xe: từ lúc xe vào bãi (PARKED) tới lúc lấy xe (COMPLETED). */
@Entity
@Table(name = "parking_session")
public class ParkingSession {

    @Id
    private UUID id;

    @Column(name = "session_code", nullable = false, unique = true, length = 20)
    private String sessionCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lot_id", nullable = false)
    private ParkingLot lot;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Column(name = "plate_number", length = 20)
    private String plateNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "monthly_pass_id")
    private ParkingMonthlyPass monthlyPass;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "metro_ticket_id")
    private Ticket metroTicket;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParkingSessionStatus status;

    @Column(name = "check_in_at", nullable = false)
    private Instant checkInAt;

    @Column(name = "check_out_at")
    private Instant checkOutAt;

    @Column(name = "fee_vnd")
    private Integer feeVnd;

    @Column(name = "discount_vnd")
    private Integer discountVnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "checked_in_by")
    private UUID checkedInBy;

    @Column(name = "checked_out_by")
    private UUID checkedOutBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ParkingSession() {
    }

    public ParkingSession(ParkingLot lot, VehicleType vehicleType, String plateNumber, ParkingMonthlyPass monthlyPass,
            UUID checkedInBy, Instant checkInAt) {
        this.id = UUID.randomUUID();
        this.sessionCode = SecureCodes.humanCode("PK", 8);
        this.lot = lot;
        this.vehicleType = vehicleType;
        this.plateNumber = plateNumber;
        this.monthlyPass = monthlyPass;
        this.checkedInBy = checkedInBy;
        this.checkInAt = checkInAt;
        this.status = ParkingSessionStatus.PARKED;
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

    public void complete(Instant checkOutAt, int feeVnd, int discountVnd, PaymentMethod method, Ticket metroTicket,
            UUID checkedOutBy) {
        this.checkOutAt = checkOutAt;
        this.feeVnd = feeVnd;
        this.discountVnd = discountVnd;
        this.paymentMethod = method;
        this.metroTicket = metroTicket;
        this.checkedOutBy = checkedOutBy;
        this.status = ParkingSessionStatus.COMPLETED;
    }

    public UUID getId() { return id; }
    public String getSessionCode() { return sessionCode; }
    public ParkingLot getLot() { return lot; }
    public VehicleType getVehicleType() { return vehicleType; }
    public String getPlateNumber() { return plateNumber; }
    public ParkingMonthlyPass getMonthlyPass() { return monthlyPass; }
    public Ticket getMetroTicket() { return metroTicket; }
    public ParkingSessionStatus getStatus() { return status; }
    public Instant getCheckInAt() { return checkInAt; }
    public Instant getCheckOutAt() { return checkOutAt; }
    public Integer getFeeVnd() { return feeVnd; }
    public Integer getDiscountVnd() { return discountVnd; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
}
