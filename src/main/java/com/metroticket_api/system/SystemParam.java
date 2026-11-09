package com.metroticket_api.system;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Một phiên bản giá trị của tham số. Dòng có effective_to rỗng là phiên bản mới nhất. */
@Entity
@Table(name = "system_param")
public class SystemParam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "param_key", nullable = false, length = 100)
    private String paramKey;

    @Column(name = "param_group", nullable = false, length = 30)
    private String paramGroup;

    @Column(name = "param_value", nullable = false)
    private String paramValue;

    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType;

    private String description;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SystemParam() {
    }

    /** Phiên bản kế tiếp của {@code previous}. */
    SystemParam(SystemParam previous, String value, Instant effectiveFrom, UUID updatedBy, Instant createdAt) {
        this.paramKey = previous.paramKey;
        this.paramGroup = previous.paramGroup;
        this.valueType = previous.valueType;
        this.description = previous.description;
        this.paramValue = value;
        this.effectiveFrom = effectiveFrom;
        this.updatedBy = updatedBy;
        this.createdAt = createdAt;
    }

    void close(Instant at) {
        this.effectiveTo = at;
    }

    public Long getId() { return id; }
    public String getParamKey() { return paramKey; }
    public String getParamGroup() { return paramGroup; }
    public String getParamValue() { return paramValue; }
    public String getValueType() { return valueType; }
    public String getDescription() { return description; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public Instant getEffectiveTo() { return effectiveTo; }
    public UUID getUpdatedBy() { return updatedBy; }
}
