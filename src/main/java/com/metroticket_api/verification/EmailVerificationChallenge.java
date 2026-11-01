package com.metroticket_api.verification;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Một lần cấp mã xác thực. Chỉ lưu HMAC của mã, không bao giờ lưu mã gốc.
 */
@Entity
@Table(name = "email_verification_challenge")
public class EmailVerificationChallenge {

    @Id
    private UUID id;

    @Column(nullable = false, length = 320)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VerificationPurpose purpose;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Người yêu cầu mã, chỉ có ở luồng đã đăng nhập (đổi email). */
    @Column(name = "requested_by")
    private UUID requestedBy;

    /** Hash của ticket đăng nhập 2 lớp, chỉ có ở mục đích LOGIN_2FA. */
    @Column(name = "login_ticket_hash", length = 64)
    private String loginTicketHash;

    // Khoá lạc quan: chặn 2 request verify song song cùng dùng một mã
    @Version
    private long version;

    protected EmailVerificationChallenge() {
        // cho JPA
    }

    public EmailVerificationChallenge(String email, VerificationPurpose purpose, UUID requestedBy, String codeHash,
                                      Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.requestedBy = requestedBy;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public void bindLoginTicket(String ticketHash) {
        loginTicketHash = ticketHash;
    }

    public void recordFailedAttempt() {
        failedAttempts++;
    }

    public void consume(Instant now) {
        consumedAt = now;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public VerificationPurpose getPurpose() { return purpose; }
    public String getCodeHash() { return codeHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getFailedAttempts() { return failedAttempts; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getRequestedBy() { return requestedBy; }
}
