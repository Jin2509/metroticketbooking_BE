package com.metroticket_api.verification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmailVerificationChallengeRepository extends JpaRepository<EmailVerificationChallenge, UUID> {

    /** Mã mới nhất chưa dùng của một email cho một mục đích. */
    Optional<EmailVerificationChallenge> findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            String email, VerificationPurpose purpose);

    /** Vô hiệu các mã cũ khi cấp mã mới, để chỉ mã mới nhất còn dùng được. */
    @Modifying
    @Query("""
            update EmailVerificationChallenge c set c.consumedAt = :now
            where c.email = :email and c.purpose = :purpose and c.consumedAt is null
            """)
    int invalidateActive(String email, VerificationPurpose purpose, Instant now);

    /** Dọn rác định kỳ. */
    @Modifying
    @Query("delete from EmailVerificationChallenge c where c.expiresAt < :before")
    int deleteExpiredBefore(Instant before);
}
