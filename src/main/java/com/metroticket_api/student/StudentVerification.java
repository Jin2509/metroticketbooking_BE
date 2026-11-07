package com.metroticket_api.student;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.metroticket_api.ai.StudentCardExtraction;

@Entity
@Table(name = "student_verification")
public class StudentVerification {

    public enum DecisionSource { AI, MANUAL }

    @Id private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false) private UUID userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private StudentVerificationStatus status;
    @Column(name = "declared_full_name", nullable = false, length = 120) private String declaredFullName;
    @Column(name = "declared_student_code", nullable = false, length = 30) private String declaredStudentCode;
    @Column(name = "declared_school", nullable = false, length = 200) private String declaredSchool;
    @Column(name = "image_mime_type", nullable = false, length = 20) private String imageMimeType;

    @Column(name = "ai_attempts", nullable = false) private int aiAttempts;
    @Column(name = "ai_next_attempt_at") private Instant aiNextAttemptAt;
    @Column(name = "ai_locked_at") private Instant aiLockedAt;
    @Column(name = "ai_model", length = 60) private String aiModel;
    @Column(name = "ai_is_student_card") private Boolean aiIsStudentCard;
    @Column(name = "ai_full_name", length = 200) private String aiFullName;
    @Column(name = "ai_student_code", length = 60) private String aiStudentCode;
    @Column(name = "ai_school", length = 300) private String aiSchool;
    @Column(name = "ai_valid_until") private LocalDate aiValidUntil;
    @Column(name = "ai_confidence", precision = 3, scale = 2) private BigDecimal aiConfidence;
    @Column(name = "ai_tampering_suspected") private Boolean aiTamperingSuspected;
    @Column(name = "ai_notes", columnDefinition = "TEXT") private String aiNotes;
    @Column(name = "ai_error", length = 500) private String aiError;
    @Column(name = "ai_completed_at") private Instant aiCompletedAt;

    @Enumerated(EnumType.STRING) @Column(name = "decision_source", length = 10) private DecisionSource decisionSource;
    @Column(name = "decision_reasons", columnDefinition = "TEXT") private String decisionReasons;
    @Column(name = "reviewed_by") private UUID reviewedBy;
    @Column(name = "review_note", length = 1000) private String reviewNote;
    @Column(name = "approved_until") private LocalDate approvedUntil;
    @Column(name = "submitted_at", nullable = false, updatable = false) private Instant submittedAt;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "image_purged_at") private Instant imagePurgedAt;
    @Version @Column(nullable = false) private long version;

    protected StudentVerification() {
    }

    public StudentVerification(UUID userId, String fullName, String studentCode, String school, String mimeType,
            Instant now, boolean queueForAi) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.declaredFullName = fullName;
        this.declaredStudentCode = studentCode;
        this.declaredSchool = school;
        this.imageMimeType = mimeType;
        this.submittedAt = now;
        if (queueForAi) {
            this.status = StudentVerificationStatus.PENDING;
            this.aiNextAttemptAt = now;
        } else {
            this.status = StudentVerificationStatus.NEEDS_REVIEW;
            this.decisionReasons = "AI chưa được cấu hình, chờ duyệt tay";
        }
    }

    public void recordAi(StudentCardExtraction ai, Instant at) {
        aiModel = ai.model();
        aiIsStudentCard = ai.studentCard();
        aiFullName = ai.fullName();
        aiStudentCode = ai.studentCode();
        aiSchool = ai.schoolName();
        aiValidUntil = ai.validUntil();
        aiConfidence = ai.confidence();
        aiTamperingSuspected = ai.tamperingSuspected();
        aiNotes = ai.notes();
        aiError = null;
        aiCompletedAt = at;
        aiLockedAt = null;
    }

    /** Lỗi tạm thời: xếp hàng gọi lại sau. */
    public void retryLater(String error, Instant nextAttemptAt) {
        status = StudentVerificationStatus.PENDING;
        aiError = truncate(error);
        aiNextAttemptAt = nextAttemptAt;
        aiLockedAt = null;
    }

    public void needsReview(String reasons, String error) {
        status = StudentVerificationStatus.NEEDS_REVIEW;
        decisionReasons = reasons;
        if (error != null) {
            aiError = truncate(error);
        }
        aiLockedAt = null;
    }

    public void approve(DecisionSource source, LocalDate until, String reasons, UUID reviewer, String note, Instant at) {
        status = StudentVerificationStatus.APPROVED;
        decide(source, reasons, reviewer, note, at);
        approvedUntil = until;
    }

    public void reject(DecisionSource source, String reasons, UUID reviewer, String note, Instant at) {
        status = StudentVerificationStatus.REJECTED;
        decide(source, reasons, reviewer, note, at);
    }

    public void revoke(UUID reviewer, String note, Instant at) {
        status = StudentVerificationStatus.REVOKED;
        reviewedBy = reviewer;
        reviewNote = note;
        decidedAt = at;
    }

    public void cancel(Instant at) {
        status = StudentVerificationStatus.CANCELLED;
        decidedAt = at;
        aiLockedAt = null;
    }

    public void markImagePurged(Instant at) {
        imagePurgedAt = at;
    }

    private void decide(DecisionSource source, String reasons, UUID reviewer, String note, Instant at) {
        decisionSource = source;
        decisionReasons = reasons;
        reviewedBy = reviewer;
        reviewNote = note;
        decidedAt = at;
        aiLockedAt = null;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public StudentVerificationStatus getStatus() { return status; }
    public String getDeclaredFullName() { return declaredFullName; }
    public String getDeclaredStudentCode() { return declaredStudentCode; }
    public String getDeclaredSchool() { return declaredSchool; }
    public String getImageMimeType() { return imageMimeType; }
    public int getAiAttempts() { return aiAttempts; }
    public Instant getAiNextAttemptAt() { return aiNextAttemptAt; }
    public String getAiModel() { return aiModel; }
    public Boolean getAiIsStudentCard() { return aiIsStudentCard; }
    public String getAiFullName() { return aiFullName; }
    public String getAiStudentCode() { return aiStudentCode; }
    public String getAiSchool() { return aiSchool; }
    public LocalDate getAiValidUntil() { return aiValidUntil; }
    public BigDecimal getAiConfidence() { return aiConfidence; }
    public Boolean getAiTamperingSuspected() { return aiTamperingSuspected; }
    public String getAiNotes() { return aiNotes; }
    public String getAiError() { return aiError; }
    public Instant getAiCompletedAt() { return aiCompletedAt; }
    public DecisionSource getDecisionSource() { return decisionSource; }
    public String getDecisionReasons() { return decisionReasons; }
    public UUID getReviewedBy() { return reviewedBy; }
    public String getReviewNote() { return reviewNote; }
    public LocalDate getApprovedUntil() { return approvedUntil; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getImagePurgedAt() { return imagePurgedAt; }
}
