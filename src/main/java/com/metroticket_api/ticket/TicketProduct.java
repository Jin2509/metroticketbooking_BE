package com.metroticket_api.ticket;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Loại vé theo thời gian: thời lượng = durationValue × durationUnit (1 ngày, 3 ngày, 1 tuần, 1 tháng...).
 * Mã loại vé không đổi được sau khi tạo; thời lượng chỉ đổi được khi chưa bán vé nào.
 */
@Entity
@Table(name = "ticket_product")
public class TicketProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "duration_unit", nullable = false, length = 10)
    private DurationUnit durationUnit;

    @Column(name = "duration_value", nullable = false)
    private int durationValue;

    /** Giá một kỳ. */
    @Column(name = "price_vnd", nullable = false)
    private int priceVnd;

    /** Số kỳ tối đa mua trong một lần (chỉ vé tháng được > 1). */
    @Column(name = "max_periods", nullable = false)
    private int maxPeriods = 1;

    /** Phải quẹt vào lần đầu trong số ngày này kể từ lúc mua, quá hạn thì vé hết hiệu lực. */
    @Column(name = "activation_window_days", nullable = false)
    private int activationWindowDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status = ProductStatus.ACTIVE;

    /** Vé lượt: giá tính theo bảng fare_band tại lúc mua, không dùng priceVnd. */
    @Column(name = "single_journey", nullable = false)
    private boolean singleJourney;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TicketProduct() {
    }

    public TicketProduct(String code, String name, String description, DurationUnit durationUnit, int durationValue,
            int priceVnd, int maxPeriods, int activationWindowDays, ProductStatus status, UUID createdBy) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.durationUnit = durationUnit;
        this.durationValue = durationValue;
        this.priceVnd = priceVnd;
        this.maxPeriods = maxPeriods;
        this.activationWindowDays = activationWindowDays;
        this.status = status == null ? ProductStatus.ACTIVE : status;
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
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

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public DurationUnit getDurationUnit() { return durationUnit; }
    public int getDurationValue() { return durationValue; }
    public int getPriceVnd() { return priceVnd; }
    public boolean isSingleJourney() { return singleJourney; }
    public int getMaxPeriods() { return maxPeriods; }
    public int getActivationWindowDays() { return activationWindowDays; }
    public ProductStatus getStatus() { return status; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setDuration(DurationUnit unit, int value) { this.durationUnit = unit; this.durationValue = value; }
    public void setPriceVnd(int priceVnd) { this.priceVnd = priceVnd; }
    public void setMaxPeriods(int maxPeriods) { this.maxPeriods = maxPeriods; }
    public void setActivationWindowDays(int days) { this.activationWindowDays = days; }
    public void setStatus(ProductStatus status) { this.status = status; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }

    /** "1 tháng", "3 ngày"... dùng cho thông báo lỗi và nhật ký. */
    public String durationLabel() {
        return durationValue + " " + switch (durationUnit) {
            case DAY -> "ngày";
            case WEEK -> "tuần";
            case MONTH -> "tháng";
        };
    }
}
