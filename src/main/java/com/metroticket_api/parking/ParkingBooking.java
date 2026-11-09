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

/** Lượt đặt chỗ gửi xe trả trước (UC14), nhân viên quét QR khi gửi và lấy xe (UC17). */
@Entity
@Table(name = "parking_booking")
public class ParkingBooking {

    @Id
    private UUID id;

    @Column(name = "booking_code", nullable = false, unique = true, length = 20)
    private String bookingCode;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lot_id", nullable = false)
    private ParkingLot lot;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Column(name = "plate_number", length = 20)
    private String plateNumber;

    @Column(name = "price_vnd", nullable = false)
    private int priceVnd;

    @Column(name = "overtime_fee_vnd", nullable = false)
    private int overtimeFeeVnd;

    @Column(name = "qr_token", nullable = false, unique = true, length = 64)
    private String qrToken;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "checkin_at")
    private Instant checkinAt;

    @Column(name = "checkout_at")
    private Instant checkoutAt;

    @Column(name = "checkin_by")
    private UUID checkinBy;

    @Column(name = "checkout_by")
    private UUID checkoutBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ParkingBookingStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ParkingBooking() {
    }

    ParkingBooking(UUID userId, ParkingLot lot, VehicleType vehicleType, String plateNumber, int priceVnd, Instant startAt,
            Instant endAt) {
        this.id = UUID.randomUUID();
        this.bookingCode = SecureCodes.humanCode("PB", 8);
        this.qrToken = SecureCodes.qrToken();
        this.userId = userId;
        this.lot = lot;
        this.vehicleType = vehicleType;
        this.plateNumber = plateNumber;
        this.priceVnd = priceVnd;
        this.startAt = startAt;
        this.endAt = endAt;
        this.status = ParkingBookingStatus.PENDING_PAYMENT;
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

    void markPaid() {
        status = ParkingBookingStatus.PAID;
    }

    void cancelUnpaid() {
        if (status == ParkingBookingStatus.PENDING_PAYMENT) {
            status = ParkingBookingStatus.CANCELLED;
        }
    }

    void checkIn(Instant now, UUID by) {
        status = ParkingBookingStatus.CHECKED_IN;
        checkinAt = now;
        checkinBy = by;
    }

    void complete(Instant now, UUID by, int overtimeFee) {
        status = ParkingBookingStatus.COMPLETED;
        checkoutAt = now;
        checkoutBy = by;
        overtimeFeeVnd = overtimeFee;
    }

    public UUID getId() { return id; }
    public String getBookingCode() { return bookingCode; }
    public UUID getUserId() { return userId; }
    public ParkingLot getLot() { return lot; }
    public VehicleType getVehicleType() { return vehicleType; }
    public String getPlateNumber() { return plateNumber; }
    public int getPriceVnd() { return priceVnd; }
    public int getOvertimeFeeVnd() { return overtimeFeeVnd; }
    public String getQrToken() { return qrToken; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }
    public Instant getCheckinAt() { return checkinAt; }
    public Instant getCheckoutAt() { return checkoutAt; }
    public ParkingBookingStatus getStatus() { return status; }
}
