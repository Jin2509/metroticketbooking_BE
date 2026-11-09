package com.metroticket_api.ticket;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Một bậc giá vé lượt theo quãng đường, khoảng [min, max); max rỗng là không giới hạn. */
@Entity
@Table(name = "fare_band")
public class FareBand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "min_distance_km", nullable = false, precision = 8, scale = 3)
    private BigDecimal minDistanceKm;

    @Column(name = "max_distance_km", precision = 8, scale = 3)
    private BigDecimal maxDistanceKm;

    @Column(name = "price_vnd", nullable = false)
    private int priceVnd;

    protected FareBand() {
    }

    FareBand(BigDecimal minDistanceKm, BigDecimal maxDistanceKm, int priceVnd) {
        this.minDistanceKm = minDistanceKm;
        this.maxDistanceKm = maxDistanceKm;
        this.priceVnd = priceVnd;
    }

    boolean contains(BigDecimal km) {
        return km.compareTo(minDistanceKm) >= 0 && (maxDistanceKm == null || km.compareTo(maxDistanceKm) < 0);
    }

    public BigDecimal getMinDistanceKm() { return minDistanceKm; }
    public BigDecimal getMaxDistanceKm() { return maxDistanceKm; }
    public int getPriceVnd() { return priceVnd; }
}
