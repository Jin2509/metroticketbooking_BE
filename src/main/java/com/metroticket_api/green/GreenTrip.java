package com.metroticket_api.green;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.metroticket_api.metro.station.Station;

/** Một chuyến đi Metro đã hoàn thành (quẹt vào → quẹt ra), kèm lượng CO2 tiết kiệm và điểm xanh. */
@Entity
@Table(name = "green_trip")
public class GreenTrip {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "ticket_id", nullable = false) private UUID ticketId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "entry_station_id", nullable = false) private Station entryStation;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "exit_station_id", nullable = false) private Station exitStation;
    @Column(name = "entry_at", nullable = false) private Instant entryAt;
    @Column(name = "exit_at", nullable = false) private Instant exitAt;
    @Column(name = "distance_km", nullable = false, precision = 8, scale = 3) private BigDecimal distanceKm;
    @Column(name = "co2_saved_grams", nullable = false) private int co2SavedGrams;
    @Column(nullable = false) private int points;
    @Column(nullable = false) private boolean counted;
    @Column(name = "not_counted_reason", length = 200) private String notCountedReason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected GreenTrip() {
    }

    public GreenTrip(UUID userId, UUID ticketId, Station entry, Station exit, Instant entryAt, Instant exitAt,
            BigDecimal distanceKm, int co2SavedGrams, int points, boolean counted, String notCountedReason, Instant now) {
        this.userId = userId;
        this.ticketId = ticketId;
        this.entryStation = entry;
        this.exitStation = exit;
        this.entryAt = entryAt;
        this.exitAt = exitAt;
        this.distanceKm = distanceKm;
        this.co2SavedGrams = counted ? co2SavedGrams : 0;
        this.points = counted ? points : 0;
        this.counted = counted;
        this.notCountedReason = notCountedReason;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
    public Station getEntryStation() { return entryStation; }
    public Station getExitStation() { return exitStation; }
    public Instant getEntryAt() { return entryAt; }
    public Instant getExitAt() { return exitAt; }
    public BigDecimal getDistanceKm() { return distanceKm; }
    public int getCo2SavedGrams() { return co2SavedGrams; }
    public int getPoints() { return points; }
    public boolean isCounted() { return counted; }
    public String getNotCountedReason() { return notCountedReason; }
}
