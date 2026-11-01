package com.metroticket_api.verification;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

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

    /** Cấp mã cho luồng chưa đăng nhập (đăng ký, quên mật khẩu): mã gắn với email. */
    @Transactional
    public void issue(String rawEmail, VerificationPurpose purpose) {
        String email = normalize(rawEmail);
        Instant now = clock.instant();

        repository.findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(email, purpose)
                .ifPresent(last -> ensureCooldownPassed(last, now));
        repository.invalidateActive(email, purpose, now);
        create(email, purpose, null, now);
    }

    /**
     * Cấp mã cho luồng đã đăng nhập (đổi email): mã gắn với cả người yêu cầu, nên user khác
     * không dùng được mã này và cũng không làm mất hay khoá mã của người ta.
     */
    @Transactional
    public void issueForUser(UUID userId, String rawEmail, VerificationPurpose purpose) {
        String email = normalize(rawEmail);
        Instant now = clock.instant();

        repository.findFirstByRequestedByAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(userId, purpose)
                .ifPresent(last -> ensureCooldownPassed(last, now));
        repository.invalidateActiveOfRequester(userId, purpose, now);
        create(email, purpose, userId, now);
    }

    /**
     * Cấp mã đăng nhập 2 lớp sau khi đã đúng mật khẩu, trả về ticket để gửi kèm mã ở bước 2.
     * Vừa gửi mã xong mà đăng nhập lại thì chỉ đổi ticket, không gửi thêm mail và không báo 429:
     * người dùng đã nhập đúng mật khẩu, mã trong mail vẫn dùng được.
     */
    @Transactional
    public LoginChallenge issueLogin(UUID userId, String rawEmail) {
        String email = normalize(rawEmail);
        Instant now = clock.instant();
        String ticket = newLoginTicket();

        EmailVerificationChallenge current = repository
                .findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(email, VerificationPurpose.LOGIN_2FA)
                .filter(last -> now.isBefore(last.getCreatedAt().plus(props.resendCooldown())))
                .orElseGet(() -> {
                    repository.invalidateActive(email, VerificationPurpose.LOGIN_2FA, now);
                    return create(email, VerificationPurpose.LOGIN_2FA, userId, now);
                });
        current.bindLoginTicket(loginTicketHash(ticket));
        return new LoginChallenge(ticket, current.getExpiresAt());
    }

    /** Gửi lại mã cho ticket đang có, ticket giữ nguyên. */
    @Transactional
    public void reissueLogin(EmailVerificationChallenge current, String ticket) {
        Instant now = clock.instant();
        ensureCooldownPassed(current, now);
        repository.invalidateActive(current.getEmail(), VerificationPurpose.LOGIN_2FA, now);
        create(current.getEmail(), VerificationPurpose.LOGIN_2FA, current.getRequestedBy(), now)
                .bindLoginTicket(loginTicketHash(ticket));
    }

    @Transactional(readOnly = true)
    public Optional<EmailVerificationChallenge> findLogin(String ticket) {
        return repository.findFirstByLoginTicketHashAndPurposeAndConsumedAtIsNull(
                loginTicketHash(ticket), VerificationPurpose.LOGIN_2FA);
    }

    @Transactional
    public VerificationResult verifyLogin(EmailVerificationChallenge challenge, String code) {
        return check(Optional.of(challenge), challenge.getEmail(), VerificationPurpose.LOGIN_2FA, code);
    }

    /** Thời gian còn lại của mã đang hiệu lực (nếu có). */
    @Transactional(readOnly = true)
    public Optional<Duration> activeCodeRemaining(String rawEmail, VerificationPurpose purpose) {
        Instant now = clock.instant();
        return repository.findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(normalize(rawEmail), purpose)
                .filter(c -> !c.isExpired(now))
                .map(c -> Duration.between(now, c.getExpiresAt()));
    }

    /**
     * Trả về kết quả thay vì ném exception, để số lần nhập sai vẫn được commit
     * (ném RuntimeException trong @Transactional sẽ rollback cả lần đếm sai).
     */
    @Transactional
    public VerificationResult verify(String rawEmail, VerificationPurpose purpose, String code) {
        String email = normalize(rawEmail);
        return check(repository.findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(email, purpose),
                email, purpose, code);
    }

    /** Như verify, nhưng chỉ chấp nhận yêu cầu mới nhất của chính user này và đúng email đó. */
    @Transactional
    public VerificationResult verifyForUser(UUID userId, String rawEmail, VerificationPurpose purpose, String code) {
        String email = normalize(rawEmail);
        return check(repository.findFirstByRequestedByAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(userId, purpose)
                        .filter(c -> c.getEmail().equals(email)),
                email, purpose, code);
    }

    private void ensureCooldownPassed(EmailVerificationChallenge last, Instant now) {
        Instant allowedAt = last.getCreatedAt().plus(props.resendCooldown());
        if (now.isBefore(allowedAt)) {
            throw new ResendTooSoonException(Duration.between(now, allowedAt));
        }
    }

    private EmailVerificationChallenge create(String email, VerificationPurpose purpose, UUID requestedBy, Instant now) {
        String code = "%06d".formatted(random.nextInt(1_000_000));
        // Phải dùng bản save trả về: id gán sẵn nên save là merge, đối tượng truyền vào không được quản lý
        EmailVerificationChallenge saved = repository.save(new EmailVerificationChallenge(
                email, purpose, requestedBy, hash(email, purpose, code), now, now.plus(props.lifetime())));

        events.publishEvent(new VerificationCodeIssued(email, purpose, code, props.lifetime()));
        return saved;
    }

    private VerificationResult check(Optional<EmailVerificationChallenge> found, String email,
                                     VerificationPurpose purpose, String code) {
        Instant now = clock.instant();
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

    private String newLoginTicket() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String loginTicketHash(String ticket) {
        return hash("ticket", VerificationPurpose.LOGIN_2FA, ticket.trim());
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

    public record LoginChallenge(String ticket, Instant expiresAt) {
    }
}
