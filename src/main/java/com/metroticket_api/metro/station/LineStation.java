package com.metroticket_api.metro.station;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.metroticket_api.metro.line.MetroLine;

@Entity
@Table(name = "line_station")
public class LineStation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_id", nullable = false)
    private MetroLine line;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @Column(name = "station_order", nullable = false)
    private Integer stationOrder;

    @Column(name = "distance_from_prev_km", precision = 8, scale = 3)
    private BigDecimal distanceFromPrevKm;

    @Column(name = "travel_time_from_prev_seconds")
    private Integer travelTimeFromPrevSeconds;

    @Column(name = "dwell_time_seconds", nullable = false)
    private Integer dwellTimeSeconds = 30;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LineStation() {
    }

    public LineStation(MetroLine line, Station station, Integer stationOrder,
            BigDecimal distanceFromPrevKm, Integer travelTimeFromPrevSeconds, Integer dwellTimeSeconds) {
        this.line = line;
        this.station = station;
        this.stationOrder = stationOrder;
        this.distanceFromPrevKm = distanceFromPrevKm;
        this.travelTimeFromPrevSeconds = travelTimeFromPrevSeconds;
        this.dwellTimeSeconds = dwellTimeSeconds == null ? 30 : dwellTimeSeconds;
    }

    public Long getId() {
        return id;
    }

    public MetroLine getLine() {
        return line;
    }

    public Station getStation() {
        return station;
    }

    public Integer getStationOrder() {
        return stationOrder;
    }

    public void setStationOrder(Integer stationOrder) {
        this.stationOrder = stationOrder;
    }

    public BigDecimal getDistanceFromPrevKm() {
        return distanceFromPrevKm;
    }

    public void setDistanceFromPrevKm(BigDecimal distanceFromPrevKm) {
        this.distanceFromPrevKm = distanceFromPrevKm;
    }

    public Integer getTravelTimeFromPrevSeconds() {
        return travelTimeFromPrevSeconds;
    }

    public void setTravelTimeFromPrevSeconds(Integer travelTimeFromPrevSeconds) {
        this.travelTimeFromPrevSeconds = travelTimeFromPrevSeconds;
    }

    public Integer getDwellTimeSeconds() {
        return dwellTimeSeconds;
    }

    public void setDwellTimeSeconds(Integer dwellTimeSeconds) {
        this.dwellTimeSeconds = dwellTimeSeconds == null ? 30 : dwellTimeSeconds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
