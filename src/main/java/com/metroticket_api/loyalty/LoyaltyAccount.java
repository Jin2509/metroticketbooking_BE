package com.metroticket_api.loyalty;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "loyalty_account")
public class LoyaltyAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tier_id")
    private MembershipTier tier;

    @Column(name = "points_balance", nullable = false)
    private int pointsBalance;

    /** Tổng điểm đã tích, không giảm khi tiêu điểm nên dùng điểm không làm tụt hạng. */
    @Column(name = "lifetime_points", nullable = false)
    private int lifetimePoints;

    @Column(name = "tier_updated_at")
    private Instant tierUpdatedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoyaltyAccount() {
    }

    void earn(int points, Instant now) {
        pointsBalance += points;
        lifetimePoints += points;
        updatedAt = now;
    }

    void spend(int points, Instant now) {
        pointsBalance -= points;
        updatedAt = now;
    }

    /** Trả lại điểm của đơn bị huỷ: tổng tích lũy không đổi vì đây không phải điểm mới. */
    void giveBack(int points, Instant now) {
        pointsBalance += points;
        updatedAt = now;
    }

    void changeTier(MembershipTier newTier, Instant now) {
        tier = newTier;
        tierUpdatedAt = now;
    }

    public UUID getUserId() { return userId; }
    public MembershipTier getTier() { return tier; }
    public int getPointsBalance() { return pointsBalance; }
    public int getLifetimePoints() { return lifetimePoints; }
}
