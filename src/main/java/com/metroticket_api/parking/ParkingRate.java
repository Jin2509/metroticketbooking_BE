package com.metroticket_api.parking;

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

/** Sức chứa + bảng giá của một loại xe tại một bãi. Dòng này cũng là "khoá" khi kiểm tra chỗ trống. */
@Entity
@Table(name = "parking_rate")
public class ParkingRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lot_id", nullable = false)
    private ParkingLot lot;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "day_price_vnd", nullable = false)
    private int dayPriceVnd;

    @Column(name = "overnight_price_vnd", nullable = false)
    private int overnightPriceVnd;

    @Column(name = "monthly_price_vnd", nullable = false)
    private int monthlyPriceVnd;

    @Column(name = "park_and_ride_discount_percent", nullable = false)
    private int parkAndRideDiscountPercent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ParkingRate() {
    }

    public ParkingRate(ParkingLot lot, VehicleType vehicleType) {
        this.lot = lot;
        this.vehicleType = vehicleType;
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

    public void update(int capacity, int dayPriceVnd, int overnightPriceVnd, int monthlyPriceVnd, int discountPercent) {
        this.capacity = capacity;
        this.dayPriceVnd = dayPriceVnd;
        this.overnightPriceVnd = overnightPriceVnd;
        this.monthlyPriceVnd = monthlyPriceVnd;
        this.parkAndRideDiscountPercent = discountPercent;
    }

    public Long getId() { return id; }
    public ParkingLot getLot() { return lot; }
    public VehicleType getVehicleType() { return vehicleType; }
    public int getCapacity() { return capacity; }
    public int getDayPriceVnd() { return dayPriceVnd; }
    public int getOvernightPriceVnd() { return overnightPriceVnd; }
    public int getMonthlyPriceVnd() { return monthlyPriceVnd; }
    public int getParkAndRideDiscountPercent() { return parkAndRideDiscountPercent; }
}
