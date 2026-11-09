package com.metroticket_api.loyalty;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Lịch sử cộng, trừ, hoàn điểm. */
@Entity
@Table(name = "loyalty_transaction")
public class LoyaltyTransaction {

    public enum Type { EARN, REDEEM, RESTORE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "validation_id")
    private Long validationId;

    @Column(name = "reference_type", length = 30)
    private String referenceType;

    @Column(name = "reference_id", length = 64)
    private String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private Type type;

    @Column(nullable = false)
    private int points;

    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoyaltyTransaction() {
    }

    LoyaltyTransaction(UUID userId, Long validationId, String referenceType, String referenceId, Type type, int points,
            int balanceAfter, String description, Instant createdAt) {
        this.userId = userId;
        this.validationId = validationId;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.type = type;
        this.points = points;
        this.balanceAfter = balanceAfter;
        this.description = description;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Type getType() { return type; }
    public int getPoints() { return points; }
    public int getBalanceAfter() { return balanceAfter; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
}
