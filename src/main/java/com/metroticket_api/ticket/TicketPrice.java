package com.metroticket_api.ticket;

import java.time.Instant;
import java.util.UUID;

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
import jakarta.persistence.Table;

/** Bảng giá do Quản trị viên lập, có hiệu lực sau khi Quản lý duyệt (UC08). */
@Entity
@Table(name = "periodic_ticket_price")
public class TicketPrice {

    public enum Status { PENDING_APPROVAL, APPROVED, APPLIED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private TicketProduct product;

    @Column(name = "price_vnd", nullable = false)
    private int priceVnd;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "applied_at")
    private Instant appliedAt;

    protected TicketPrice() {
    }

    TicketPrice(TicketProduct product, int priceVnd, Instant effectiveFrom, UUID createdBy, Instant submittedAt) {
        this.product = product;
        this.priceVnd = priceVnd;
        this.effectiveFrom = effectiveFrom;
        this.createdBy = createdBy;
        this.submittedAt = submittedAt;
        this.status = Status.PENDING_APPROVAL;
    }

    void approve(UUID by, Instant at) {
        status = Status.APPROVED;
        approvedBy = by;
        approvedAt = at;
    }

    void reject(UUID by, String reason, Instant at) {
        status = Status.REJECTED;
        approvedBy = by;
        approvedAt = at;
        rejectionReason = reason;
    }

    void markApplied(Instant at) {
        status = Status.APPLIED;
        appliedAt = at;
    }

    public Long getId() { return id; }
    public TicketProduct getProduct() { return product; }
    public int getPriceVnd() { return priceVnd; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public Status getStatus() { return status; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getAppliedAt() { return appliedAt; }
}
