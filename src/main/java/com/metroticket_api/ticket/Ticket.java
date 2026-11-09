package com.metroticket_api.ticket;

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
import jakarta.persistence.Version;

import com.metroticket_api.metro.station.Station;

@Entity
@Table(name = "ticket")
public class Ticket {

    @Id
    private UUID id;

    @Column(name = "ticket_code", nullable = false, unique = true, length = 20)
    private String ticketCode;

    @Column(name = "qr_token", nullable = false, unique = true, length = 64)
    private String qrToken;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private TicketProduct product;

    /** Snapshot thời lượng tại lúc mua: đổi loại vé sau này không ảnh hưởng vé đã bán. */
    @Enumerated(EnumType.STRING)
    @Column(name = "duration_unit", nullable = false, length = 10)
    private DurationUnit durationUnit;

    @Column(name = "duration_value", nullable = false)
    private int durationValue;

    /** Số kỳ đã mua (vé tháng mua 3 tháng → 3). */
    @Column(nullable = false)
    private int periods;

    @Column(name = "unit_price_vnd", nullable = false)
    private int unitPriceVnd;

    /** Giá niêm yết một kỳ tại lúc mua (trước ưu đãi). */
    @Column(name = "list_unit_price_vnd", nullable = false)
    private int listUnitPriceVnd;

    /** STUDENT hoặc null. */
    @Column(name = "discount_code", length = 20)
    private String discountCode;

    @Column(name = "discount_percent", nullable = false)
    private int discountPercent;

    /** Tổng tiền = unitPriceVnd × periods (unitPriceVnd đã trừ ưu đãi). */
    @Column(name = "price_vnd", nullable = false)
    private int priceVnd;

    /** Số điểm CPoint đã trừ vào đơn; priceVnd là số tiền còn phải trả sau khi trừ điểm. */
    @Column(name = "points_used", nullable = false)
    private int pointsUsed;

    /** Chỉ vé lượt có ga đi, ga đến và quãng đường đã mua. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origin_station_id")
    private Station originStation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_station_id")
    private Station destinationStation;

    @Column(name = "distance_km", precision = 8, scale = 3)
    private java.math.BigDecimal distanceKm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status;

    @Column(name = "purchased_at", nullable = false)
    private Instant purchasedAt;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_entry_station_id")
    private Station lastEntryStation;

    @Column(name = "last_entry_at")
    private Instant lastEntryAt;

    @Column(name = "trips_count", nullable = false)
    private int tripsCount;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Ticket() {
    }

    /**
     * @param validUntil hạn chót kích hoạt (quẹt vào lần đầu); sau khi kích hoạt được thay bằng hạn dùng thật.
     */
    public Ticket(UUID userId, TicketProduct product, int periods, Instant purchasedAt, Instant validUntil) {
        this(userId, product, periods, purchasedAt, validUntil, null, 0);
    }

    /** @param discountPercent 0–100; giá một kỳ sau ưu đãi làm tròn xuống tới 1.000 đ. */
    public Ticket(UUID userId, TicketProduct product, int periods, Instant purchasedAt, Instant validUntil,
            String discountCode, int discountPercent) {
        this.id = UUID.randomUUID();
        this.ticketCode = SecureCodes.humanCode("MT", 10);
        this.qrToken = SecureCodes.qrToken();
        this.userId = userId;
        this.product = product;
        this.durationUnit = product.getDurationUnit();
        this.durationValue = product.getDurationValue();
        this.periods = periods;
        this.listUnitPriceVnd = product.getPriceVnd();
        this.discountCode = discountPercent > 0 ? discountCode : null;
        this.discountPercent = this.discountCode == null ? 0 : discountPercent;
        this.unitPriceVnd = discountedUnitPrice(product.getPriceVnd(), this.discountPercent);
        this.priceVnd = Math.multiplyExact(this.unitPriceVnd, periods);
        this.status = TicketStatus.ACTIVE;
        this.purchasedAt = purchasedAt;
        this.validFrom = purchasedAt;
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

    // ===== Chuyển trạng thái (điều kiện nghiệp vụ được kiểm tra ở GateService / TicketService) =====

    /** Quẹt vào: lần đầu thì kích hoạt vé, hạn dùng = lúc này + thời lượng × số kỳ. */
    public void enter(Station station, Instant now) {
        if (activatedAt == null) {
            activatedAt = now;
            if (!isSingleJourney()) {
                validUntil = durationUnit.expiry(now, durationValue * periods);
            }
        }
        status = TicketStatus.IN_USE;
        lastEntryStation = station;
        lastEntryAt = now;
    }

    /** Quẹt ra: vé quay lại ACTIVE cho chuyến sau, hoặc EXPIRED nếu đã quá hạn trong lúc đi. */
    public void exit(Instant now) {
        tripsCount++;
        if (isSingleJourney()) {
            status = TicketStatus.USED;
            return;
        }
        status = now.isBefore(validUntil) ? TicketStatus.ACTIVE : TicketStatus.EXPIRED;
    }

    static int discountedUnitPrice(int listPrice, int percent) {
        if (percent <= 0) {
            return listPrice;
        }
        long discounted = (long) listPrice * (100 - percent) / 100;
        long rounded = discounted / 1000 * 1000;
        return (int) Math.max(rounded, percent >= 100 ? 0 : 1000);
    }

    /** Gắn hành trình và giá theo quãng đường, gọi trước khi trừ điểm. */
    void assignJourney(Station origin, Station destination, java.math.BigDecimal km, int fareVnd) {
        this.originStation = origin;
        this.destinationStation = destination;
        this.distanceKm = km;
        this.listUnitPriceVnd = fareVnd;
        this.unitPriceVnd = fareVnd;
        this.priceVnd = fareVnd;
    }

    public boolean isSingleJourney() {
        return originStation != null;
    }

    /** Trừ giá trị điểm vào đơn (1 điểm = 1 đồng). */
    public void applyPoints(int points) {
        this.pointsUsed = points;
        this.priceVnd -= points;
    }

    public void awaitPayment() {
        status = TicketStatus.PENDING_PAYMENT;
    }

    /** Tiền về đúng hạn: vé đang chờ thanh toán được kích hoạt. */
    public boolean confirmPayment(Instant now) {
        if (status != TicketStatus.PENDING_PAYMENT || !now.isBefore(validUntil)) {
            return false;
        }
        status = TicketStatus.ACTIVE;
        return true;
    }

    public void cancelUnpaid() {
        if (status == TicketStatus.PENDING_PAYMENT) {
            status = TicketStatus.CANCELLED;
        }
    }

    public void expire() {
        status = TicketStatus.EXPIRED;
    }

    public void refund(Instant now) {
        status = TicketStatus.REFUNDED;
        refundedAt = now;
    }

    public boolean isUnused() {
        return status == TicketStatus.ACTIVE && activatedAt == null && tripsCount == 0;
    }

    public UUID getId() { return id; }
    public String getTicketCode() { return ticketCode; }
    public String getQrToken() { return qrToken; }
    public UUID getUserId() { return userId; }
    public TicketProduct getProduct() { return product; }
    public DurationUnit getDurationUnit() { return durationUnit; }
    public int getDurationValue() { return durationValue; }
    public int getPeriods() { return periods; }
    public int getUnitPriceVnd() { return unitPriceVnd; }
    public int getListUnitPriceVnd() { return listUnitPriceVnd; }
    public String getDiscountCode() { return discountCode; }
    public int getDiscountPercent() { return discountPercent; }
    public int getPriceVnd() { return priceVnd; }
    public int getPointsUsed() { return pointsUsed; }
    public TicketStatus getStatus() { return status; }
    public Station getOriginStation() { return originStation; }
    public Station getDestinationStation() { return destinationStation; }
    public java.math.BigDecimal getDistanceKm() { return distanceKm; }
    public Instant getPurchasedAt() { return purchasedAt; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidUntil() { return validUntil; }
    public Instant getActivatedAt() { return activatedAt; }
    public Station getLastEntryStation() { return lastEntryStation; }
    public Instant getLastEntryAt() { return lastEntryAt; }
    public int getTripsCount() { return tripsCount; }
    public Instant getRefundedAt() { return refundedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
