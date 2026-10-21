package com.metroticket_api.verification;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cấp và kiểm tra mã xác thực 6 chữ số gửi qua email.
 *
 * <ul>
 *   <li>Mã sinh bằng SecureRandom, chỉ lưu HMAC-SHA256(hashKey, purpose:email:code).</li>
 *   <li>Giới hạn gửi lại (resend-cooldown) và số lần nhập sai (max-failed-attempts).</li>
 *   <li>Mail được gửi sau khi transaction commit (xem VerificationMailListener).</li>
 * </ul>
 */
@Service
public class VerificationCodeService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final EmailVerificationChallengeRepository repository;
    private final ApplicationEventPublisher events;
    private final VerificationProps props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec hmacKey;

    public VerificationCodeService(EmailVerificationChallengeRepository repository,
                                   ApplicationEventPublisher events,
                                   VerificationProps props,
                                   Clock clock) {
        this.repository = repository;
        this.events = events;
        this.props = props;
        this.clock = clock;
        this.hmacKey = new SecretKeySpec(props.hashKey().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    @Transactional
    public void issue(String rawEmail, VerificationPurpose purpose) {
        String email = normalize(rawEmail);
        Instant now = clock.instant();

        repository.findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(email, purpose)
                .ifPresent(last -> {
                    Instant allowedAt = last.getCreatedAt().plus(props.resendCooldown());
                    if (now.isBefore(allowedAt)) {
                        throw new ResendTooSoonException(Duration.between(now, allowedAt));
                    }
                });

        repository.invalidateActive(email, purpose, now);

        String code = "%06d".formatted(random.nextInt(1_000_000));
        repository.save(new EmailVerificationChallenge(
                email, purpose, hash(email, purpose, code), now, now.plus(props.lifetime())));

        events.publishEvent(new VerificationCodeIssued(email, purpose, code, props.lifetime()));
    }

    /**
     * Trả về kết quả thay vì ném exception, để số lần nhập sai vẫn được commit
     * (ném RuntimeException trong @Transactional sẽ rollback cả lần đếm sai).
     */
    @Transactional
    public VerificationResult verify(String rawEmail, VerificationPurpose purpose, String code) {
        String email = normalize(rawEmail);
        Instant now = clock.instant();

        var found = repository.findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(email, purpose);
        if (found.isEmpty()) {
            return VerificationResult.NOT_FOUND;
        }
        EmailVerificationChallenge challenge = found.get();

        if (challenge.isExpired(now)) {
            return VerificationResult.EXPIRED;
        }
        if (challenge.getFailedAttempts() >= props.maxFailedAttempts()) {
            return VerificationResult.TOO_MANY_ATTEMPTS;
        }

        String expected = challenge.getCodeHash();
        String actual = hash(email, purpose, code == null ? "" : code.trim());
        // So sánh thời gian hằng, tránh tấn công đo thời gian (timing attack)
        boolean match = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));

        if (!match) {
            challenge.recordFailedAttempt();
            return challenge.getFailedAttempts() >= props.maxFailedAttempts()
                    ? VerificationResult.TOO_MANY_ATTEMPTS
                    : VerificationResult.INVALID_CODE;
        }

        challenge.consume(now);
        return VerificationResult.SUCCESS;
    }

    private String hash(String email, VerificationPurpose purpose, String code) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM); // Mac không thread-safe → tạo mới mỗi lần
            mac.init(hmacKey);
            byte[] digest = mac.doFinal((purpose + ":" + email + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Không khởi tạo được HMAC", e);
        }
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
