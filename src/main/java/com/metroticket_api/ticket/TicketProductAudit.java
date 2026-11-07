package com.metroticket_api.ticket;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Nhật ký thay đổi loại vé: giữ lại cả khi loại vé bị xoá (không có khoá ngoại tới ticket_product). */
@Entity
@Table(name = "ticket_product_audit")
public class TicketProductAudit {

    public enum Action { CREATED, UPDATED, ACTIVATED, DEACTIVATED, DELETED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    @Column(name = "changed_by")
    private UUID changedBy;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String changes;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected TicketProductAudit() {
    }

    public TicketProductAudit(Long productId, Action action, UUID changedBy, String changes, Instant changedAt) {
        this.productId = productId;
        this.action = action;
        this.changedBy = changedBy;
        this.changes = changes;
        this.changedAt = changedAt;
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public Action getAction() { return action; }
    public UUID getChangedBy() { return changedBy; }
    public String getChanges() { return changes; }
    public Instant getChangedAt() { return changedAt; }
}
