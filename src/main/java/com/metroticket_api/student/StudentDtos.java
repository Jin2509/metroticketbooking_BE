package com.metroticket_api.student;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class StudentDtos {

    /** Kết quả cho sinh viên: không trả lý do nội bộ chi tiết của AI (tránh dò cách lách điều kiện). */
    public record MyVerificationResponse(UUID id, StudentVerificationStatus status, String declaredFullName,
            String declaredStudentCode, String declaredSchool, Instant submittedAt, Instant decidedAt,
            LocalDate approvedUntil, String message) {
    }

    public record StudentStatusResponse(boolean verified, LocalDate validUntil, int discountPercent,
            List<String> eligibleProductCodes, MyVerificationResponse latest) {
    }

    public record AdminVerificationResponse(UUID id, UUID userId, String userEmail, String userFullName,
            StudentVerificationStatus status, String declaredFullName, String declaredStudentCode,
            String declaredSchool, int aiAttempts, String aiModel, Boolean aiIsStudentCard, String aiFullName,
            String aiStudentCode, String aiSchool, LocalDate aiValidUntil, BigDecimal aiConfidence,
            Boolean aiTamperingSuspected, String aiNotes, String aiError, Instant aiCompletedAt,
            StudentVerification.DecisionSource decisionSource, String decisionReasons, UUID reviewedBy,
            String reviewNote, LocalDate approvedUntil, Instant submittedAt, Instant decidedAt, boolean imageAvailable) {
    }

    public record ApproveRequest(LocalDate approvedUntil, @Size(max = 1000) String note) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record PolicyResponse(boolean enabled, boolean autoApprove, BigDecimal minConfidence,
            List<String> acceptedSchools, boolean requireNameMatch, boolean requireAccountNameMatch,
            boolean requireStudentCodeMatch, boolean requireNotExpired, boolean requireValidUntil,
            int defaultValidityMonths, int maxValidityMonths, int discountPercent, List<String> eligibleProductCodes,
            int maxSubmissionsPer30Days, int imageRetentionDays, boolean aiConfigured, String aiModel,
            UUID updatedBy, Instant updatedAt, long version) {
        static PolicyResponse of(StudentPolicy p, boolean aiConfigured, String aiModel) {
            return new PolicyResponse(p.isEnabled(), p.isAutoApprove(), p.getMinConfidence(), p.acceptedSchoolList(),
                    p.isRequireNameMatch(), p.isRequireAccountNameMatch(), p.isRequireStudentCodeMatch(),
                    p.isRequireNotExpired(), p.isRequireValidUntil(), p.getDefaultValidityMonths(),
                    p.getMaxValidityMonths(), p.getDiscountPercent(), p.eligibleProductCodeList(),
                    p.getMaxSubmissionsPer30Days(), p.getImageRetentionDays(), aiConfigured, aiModel,
                    p.getUpdatedBy(), p.getUpdatedAt(), p.getVersion());
        }
    }

    /** Điều kiện admin nhập. null = giữ nguyên; version bắt buộc (chống ghi đè khi 2 người cùng sửa). */
    public record UpdatePolicyRequest(
            @NotNull Long version,
            Boolean enabled,
            Boolean autoApprove,
            @DecimalMin("0.50") @DecimalMax("1.00") BigDecimal minConfidence,
            @Size(max = 200) List<@NotBlank @Size(max = 200) String> acceptedSchools,
            Boolean requireNameMatch,
            Boolean requireAccountNameMatch,
            Boolean requireStudentCodeMatch,
            Boolean requireNotExpired,
            Boolean requireValidUntil,
            @Min(1) @Max(60) Integer defaultValidityMonths,
            @Min(1) @Max(60) Integer maxValidityMonths,
            @Min(0) @Max(100) Integer discountPercent,
            @Size(max = 20) List<@NotBlank @Size(max = 30) String> eligibleProductCodes,
            @Min(1) @Max(20) Integer maxSubmissionsPer30Days,
            @Min(1) @Max(365) Integer imageRetentionDays) {
    }
}
