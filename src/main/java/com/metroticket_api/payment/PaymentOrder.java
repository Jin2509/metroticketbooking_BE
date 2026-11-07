package com.metroticket_api.payment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Một lần thanh toán bằng QR: khách chuyển khoản đúng số tiền với nội dung chứa {@code orderCode}. */
@Entity
@Table(name = "payment_order")
public class PaymentOrder {

    @Id
    private UUID id;

    @Column(name = "order_code", nullable = false, unique = true, length = 20)
    private String orderCode;

    @Column(name = "user_id", nullable = false)
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
    private PaymentOrderStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "paid_amount_vnd")
    private Integer paidAmountVnd;

    @Column(name = "sepay_transaction_id")
    private Long sepayTransactionId;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentOrder() {
    }

    public PaymentOrder(String orderCode, UUID userId, PaymentPurpose purpose, String referenceId, int amountVnd,
            Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.orderCode = orderCode;
        this.userId = userId;
        this.purpose = purpose;
        this.referenceId = referenceId;
        this.amountVnd = amountVnd;
        this.expiresAt = expiresAt;
        this.status = PaymentOrderStatus.PENDING;
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

    public void markPaid(int amount, long sepayId, Instant at) {
        status = PaymentOrderStatus.PAID;
        paidAmountVnd = amount;
        sepayTransactionId = sepayId;
        paidAt = at;
    }

    public void expire() {
        status = PaymentOrderStatus.EXPIRED;
    }

    public void cancel() {
        status = PaymentOrderStatus.CANCELLED;
    }

    public UUID getId() { return id; }
    public String getOrderCode() { return orderCode; }
    public UUID getUserId() { return userId; }
    public PaymentPurpose getPurpose() { return purpose; }
    public String getReferenceId() { return referenceId; }
    public int getAmountVnd() { return amountVnd; }
    public PaymentOrderStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getPaidAt() { return paidAt; }
    public Integer getPaidAmountVnd() { return paidAmountVnd; }
    public Long getSepayTransactionId() { return sepayTransactionId; }
    public Instant getCreatedAt() { return createdAt; }
}
