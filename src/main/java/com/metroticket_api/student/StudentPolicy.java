package com.metroticket_api.student;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Điều kiện xác thực sinh viên và mức ưu đãi. Chỉ có 1 dòng (id = 1). */
@Entity
@Table(name = "student_verification_policy")
public class StudentPolicy {

    @Id
    private Short id = 1;

    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "auto_approve", nullable = false) private boolean autoApprove = true;
    @Column(name = "min_confidence", nullable = false, precision = 3, scale = 2) private BigDecimal minConfidence = new BigDecimal("0.85");
    @Column(name = "accepted_schools", nullable = false, columnDefinition = "TEXT") private String acceptedSchools = "";
    @Column(name = "require_name_match", nullable = false) private boolean requireNameMatch = true;
    @Column(name = "require_account_name_match", nullable = false) private boolean requireAccountNameMatch = true;
    @Column(name = "require_student_code_match", nullable = false) private boolean requireStudentCodeMatch = true;
    @Column(name = "require_not_expired", nullable = false) private boolean requireNotExpired = true;
    @Column(name = "require_valid_until", nullable = false) private boolean requireValidUntil = false;
    @Column(name = "default_validity_months", nullable = false) private int defaultValidityMonths = 12;
    @Column(name = "max_validity_months", nullable = false) private int maxValidityMonths = 12;
    @Column(name = "discount_percent", nullable = false) private int discountPercent = 50;
    @Column(name = "eligible_product_codes", nullable = false, columnDefinition = "TEXT") private String eligibleProductCodes = "MONTH_1";
    @Column(name = "max_submissions_per_30_days", nullable = false) private int maxSubmissionsPer30Days = 3;
    @Column(name = "image_retention_days", nullable = false) private int imageRetentionDays = 30;
    @Column(name = "updated_by") private UUID updatedBy;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version @Column(nullable = false) private long version;

    public StudentPolicy() {
    }

    public List<String> acceptedSchoolList() {
        return Arrays.stream(acceptedSchools.split("\\R")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public List<String> eligibleProductCodeList() {
        return Arrays.stream(eligibleProductCodes.split(",")).map(String::trim).map(String::toUpperCase)
                .filter(s -> !s.isEmpty()).toList();
    }

    public boolean isEnabled() { return enabled; }
    public boolean isAutoApprove() { return autoApprove; }
    public BigDecimal getMinConfidence() { return minConfidence; }
    public String getAcceptedSchools() { return acceptedSchools; }
    public boolean isRequireNameMatch() { return requireNameMatch; }
    public boolean isRequireAccountNameMatch() { return requireAccountNameMatch; }
    public boolean isRequireStudentCodeMatch() { return requireStudentCodeMatch; }
    public boolean isRequireNotExpired() { return requireNotExpired; }
    public boolean isRequireValidUntil() { return requireValidUntil; }
    public int getDefaultValidityMonths() { return defaultValidityMonths; }
    public int getMaxValidityMonths() { return maxValidityMonths; }
    public int getDiscountPercent() { return discountPercent; }
    public String getEligibleProductCodes() { return eligibleProductCodes; }
    public int getMaxSubmissionsPer30Days() { return maxSubmissionsPer30Days; }
    public int getImageRetentionDays() { return imageRetentionDays; }
    public UUID getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    public void setEnabled(boolean v) { enabled = v; }
    public void setAutoApprove(boolean v) { autoApprove = v; }
    public void setMinConfidence(BigDecimal v) { minConfidence = v; }
    public void setAcceptedSchools(List<String> schools) { acceptedSchools = String.join("\n", schools); }
    public void setRequireNameMatch(boolean v) { requireNameMatch = v; }
    public void setRequireAccountNameMatch(boolean v) { requireAccountNameMatch = v; }
    public void setRequireStudentCodeMatch(boolean v) { requireStudentCodeMatch = v; }
    public void setRequireNotExpired(boolean v) { requireNotExpired = v; }
    public void setRequireValidUntil(boolean v) { requireValidUntil = v; }
    public void setDefaultValidityMonths(int v) { defaultValidityMonths = v; }
    public void setMaxValidityMonths(int v) { maxValidityMonths = v; }
    public void setDiscountPercent(int v) { discountPercent = v; }
    public void setEligibleProductCodes(List<String> codes) { eligibleProductCodes = String.join(",", codes); }
    public void setMaxSubmissionsPer30Days(int v) { maxSubmissionsPer30Days = v; }
    public void setImageRetentionDays(int v) { imageRetentionDays = v; }
    public void touch(UUID by, Instant at) { updatedBy = by; updatedAt = at; }
}
