package com.metroticket_api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metroticket_api.security.AccessTokenDenylist;
import com.metroticket_api.security.JwtProps;

/**
 * Cấp, xoay vòng và thu hồi refresh token.
 *
 * <ul>
 *   <li>Token là 32 byte ngẫu nhiên (base64url), DB chỉ lưu SHA-256.</li>
 *   <li>Mỗi lần refresh: thu hồi token cũ, cấp token mới cùng family.</li>
 *   <li>Token đã thu hồi mà bị dùng lại → coi như bị lộ, thu hồi cả family (đăng xuất phiên đó).</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final String INVALID = "Refresh token không hợp lệ hoặc đã hết hạn";

    private final RefreshTokenRepository repository;
    private final AccessTokenDenylist denylist;
    private final AuthEventService authEvents;
    private final JwtProps props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, AccessTokenDenylist denylist,
            AuthEventService authEvents, JwtProps props, Clock clock) {
        this.repository = repository;
        this.denylist = denylist;
        this.authEvents = authEvents;
        this.props = props;
        this.clock = clock;
    }

    /** Cấp token cho một lần đăng nhập mới (family mới). */
    @Transactional
    public IssuedRefreshToken issue(UUID userId) {
        return issue(userId, UUID.randomUUID(), clock.instant());
    }

    /**
     * Đổi token cũ lấy token mới. noRollbackFor: khi phát hiện dùng lại token, việc thu hồi
     * cả family phải được commit dù ta ném AuthException.
     */
    @Transactional(noRollbackFor = AuthException.class)
    public Rotated rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken current = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, INVALID));
        if (current.isRevoked()) {
            reuseDetected(current, now);
        }
        if (current.isExpired(now)) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, INVALID);
        }
        if (repository.revokeIfActive(current.getId(), now) == 0) {
            // Request khác vừa dùng token này
            reuseDetected(current, now);
        }
        return new Rotated(current.getUserId(), issue(current.getUserId(), current.getFamilyId(), now));
    }

    /**
     * Đăng xuất một phiên: thu hồi cả family của token. Token sai/không tồn tại thì bỏ qua.
     * Trả về chủ phiên nếu thật sự có phiên vừa bị thu hồi (đăng xuất lần 2 thì rỗng).
     */
    @Transactional
    public Optional<UUID> revoke(String rawToken) {
        return repository.findByTokenHash(hash(rawToken))
                .filter(t -> repository.revokeFamily(t.getFamilyId(), clock.instant()) > 0)
                .map(RefreshToken::getUserId);
    }

    /** Đăng xuất mọi thiết bị (đổi mật khẩu, bị khoá...): thu hồi cả refresh token và access token. */
    @Transactional
    public void revokeAll(UUID userId) {
        repository.revokeAllOfUser(userId, clock.instant());
        denylist.revokeAllOfUserAfterCommit(userId);
    }

    /** Xoá token hết hạn quá 1 ngày, chạy lúc 3h30 sáng mỗi ngày. */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void purgeExpired() {
        repository.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
    }

    private void reuseDetected(RefreshToken token, Instant now) {
        log.warn("Refresh token bị dùng lại, thu hồi phiên của user {}", token.getUserId());
        repository.revokeFamily(token.getFamilyId(), now);
        authEvents.record(token.getUserId(), AuthEventType.TOKEN_REUSE_DETECTED);
        throw new AuthException(HttpStatus.UNAUTHORIZED, INVALID);
    }

    private IssuedRefreshToken issue(UUID userId, UUID familyId, Instant now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plus(props.refreshTokenTtl());
        repository.save(new RefreshToken(userId, familyId, hash(value), now, expiresAt));
        return new IssuedRefreshToken(value, expiresAt);
    }

    private static String hash(String rawToken) {
        try {
            // Token đã đủ ngẫu nhiên (256 bit) nên SHA-256 thuần là đủ, không cần BCrypt/HMAC
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Thiếu SHA-256", e);
        }
    }

    public record IssuedRefreshToken(String value, Instant expiresAt) {
    }

    public record Rotated(UUID userId, IssuedRefreshToken token) {
    }
}
