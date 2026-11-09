package com.metroticket_api.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Hạng thành viên chỉ là danh hiệu, xét theo tổng điểm tích lũy, không kèm quyền lợi. */
@Entity
@Table(name = "membership_tier")
public class MembershipTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name_tier", nullable = false, unique = true, length = 50)
    private String name;

    @Column(name = "min_points", nullable = false)
    private int minPoints;

    @Column(nullable = false, length = 20)
    private String status;

    protected MembershipTier() {
    }

    void update(String name, int minPoints) {
        this.name = name;
        this.minPoints = minPoints;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public int getMinPoints() { return minPoints; }
    public String getStatus() { return status; }
}
