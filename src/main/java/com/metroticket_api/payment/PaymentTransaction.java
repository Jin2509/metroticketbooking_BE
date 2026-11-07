package com.metroticket_api.payment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "payment_transaction")
public class PaymentTransaction {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentPurpose purpose;

    @Column(name = "reference_id", nullable = false, length = 64)
    private String referenceId;

    @Column(name = "amount_vnd", nullable = false)
    private int amountVnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "provider_ref", length = 100)
    private String providerRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    protected PaymentTransaction() {
    }

    public PaymentTransaction(UUID userId, PaymentPurpose purpose, String referenceId, int amountVnd,
            PaymentMethod method, String providerRef, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.purpose = purpose;
        this.referenceId = referenceId;
        this.amountVnd = amountVnd;
        this.method = method;
        this.status = PaymentStatus.SUCCEEDED;
        this.providerRef = providerRef;
        this.createdAt = now;
    }

    public void markRefundPending() {
        this.status = PaymentStatus.REFUND_PENDING;
    }

    public void markRefunded(Instant now) {
        this.status = PaymentStatus.REFUNDED;
        this.refundedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public PaymentPurpose getPurpose() { return purpose; }
    public String getReferenceId() { return referenceId; }
    public int getAmountVnd() { return amountVnd; }
    public PaymentMethod getMethod() { return method; }
    public PaymentStatus getStatus() { return status; }
    public String getProviderRef() { return providerRef; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRefundedAt() { return refundedAt; }
}
