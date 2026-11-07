package com.metroticket_api.green;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Hệ số Hành trình xanh (1 dòng, id = 1). */
@Entity
@Table(name = "green_journey_settings")
public class GreenJourneySettings {

    @Id private Short id = 1;
    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "baseline_grams_per_km", nullable = false) private int baselineGramsPerKm = 90;
    @Column(name = "metro_grams_per_km", nullable = false) private int metroGramsPerKm = 30;
    @Column(name = "points_per_km", nullable = false) private int pointsPerKm = 10;
    @Column(name = "min_trip_minutes", nullable = false) private int minTripMinutes = 2;
    @Column(name = "max_trip_minutes", nullable = false) private int maxTripMinutes = 240;
    @Column(name = "max_counted_trips_per_day", nullable = false) private int maxCountedTripsPerDay = 6;
    @Column(name = "tree_grams_per_year", nullable = false) private int treeGramsPerYear = 21000;
    @Column(name = "updated_by") private UUID updatedBy;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version @Column(nullable = false) private long version;

    public GreenJourneySettings() {
    }

    public boolean isEnabled() { return enabled; }
    public int getBaselineGramsPerKm() { return baselineGramsPerKm; }
    public int getMetroGramsPerKm() { return metroGramsPerKm; }
    public int getPointsPerKm() { return pointsPerKm; }
    public int getMinTripMinutes() { return minTripMinutes; }
    public int getMaxTripMinutes() { return maxTripMinutes; }
    public int getMaxCountedTripsPerDay() { return maxCountedTripsPerDay; }
    public int getTreeGramsPerYear() { return treeGramsPerYear; }
    public UUID getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    public void setEnabled(boolean v) { enabled = v; }
    public void setBaselineGramsPerKm(int v) { baselineGramsPerKm = v; }
    public void setMetroGramsPerKm(int v) { metroGramsPerKm = v; }
    public void setPointsPerKm(int v) { pointsPerKm = v; }
    public void setMinTripMinutes(int v) { minTripMinutes = v; }
    public void setMaxTripMinutes(int v) { maxTripMinutes = v; }
    public void setMaxCountedTripsPerDay(int v) { maxCountedTripsPerDay = v; }
    public void setTreeGramsPerYear(int v) { treeGramsPerYear = v; }
    public void touch(UUID by, Instant at) { updatedBy = by; updatedAt = at; }
}
