package com.metroticket_api.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Thu hồi access token trước hạn, lưu trong Redis với TTL theo thời gian sống của token
 * → tự xoá khi token hết hạn, không cần dọn rác. Có 2 mức:
 * <ul>
 *   <li>Một token (theo jti): đăng xuất.</li>
 *   <li>Mọi token của một user cấp trước một thời điểm: khoá tài khoản, đổi mật khẩu, đổi vai trò.</li>
 * </ul>
 *
 * <p>Nếu Redis lỗi thì bỏ qua (fail-open): token đã thu hồi vẫn dùng được tối đa
 * app.jwt.access-token-ttl, còn hệ thống không bị sập theo Redis.
 */
@Component
public class AccessTokenDenylist {

    private static final Logger log = LoggerFactory.getLogger(AccessTokenDenylist.class);
    private static final String JTI_PREFIX = "auth:revoked-jti:";
    private static final String USER_PREFIX = "auth:revoked-user:";
    private static final String REVOKED = "1";

    private final StringRedisTemplate redis;
    private final JwtProps props;
    private final Clock clock;

    public AccessTokenDenylist(StringRedisTemplate redis, JwtProps props, Clock clock) {
        this.redis = redis;
        this.props = props;
        this.clock = clock;
    }

    public void revoke(String jti, Instant expiresAt) {
        if (jti == null || expiresAt == null) {
            return;
        }
        Duration ttl = Duration.between(clock.instant(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        write(JTI_PREFIX + jti, REVOKED, ttl);
    }

    /**
     * Thu hồi mọi access token đã cấp cho user. Chạy sau khi transaction commit: token cấp
     * sau mốc này mang dữ liệu mới (mật khẩu, trạng thái, vai trò) và vẫn hợp lệ.
     */
    public void revokeAllOfUserAfterCommit(UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            revokeAllOfUser(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                revokeAllOfUser(userId);
            }
        });
    }

    private void revokeAllOfUser(UUID userId) {
        write(USER_PREFIX + userId, String.valueOf(clock.millis()), props.accessTokenTtl().plusMinutes(1));
    }

    public boolean isRevoked(Jwt jwt) {
        try {
            // MGET: kiểm tra cả 2 khoá trong một lượt gọi Redis cho mỗi request
            List<String> values = redis.opsForValue().multiGet(
                    Arrays.asList(JTI_PREFIX + jwt.getId(), USER_PREFIX + jwt.getSubject()));
            if (values == null) {
                return false;
            }
            return values.get(0) != null || (values.get(1) != null && issuedAtMillis(jwt) <= Long.parseLong(values.get(1)));
        } catch (DataAccessException e) {
            log.warn("Không kiểm tra được token thu hồi trong Redis: {}", e.getMessage());
            return false;
        }
    }

    /** Claim iat chuẩn chỉ chính xác tới giây, không phân biệt được token cấp ngay sau khi thu hồi. */
    private static long issuedAtMillis(Jwt jwt) {
        Object millis = jwt.getClaim(TokenService.ISSUED_AT_MILLIS_CLAIM);
        if (millis instanceof Number number) {
            return number.longValue();
        }
        return jwt.getIssuedAt() == null ? 0 : jwt.getIssuedAt().toEpochMilli();
    }

    private void write(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (DataAccessException e) {
            log.warn("Không ghi được token thu hồi vào Redis, access token sẽ hết hiệu lực khi hết hạn: {}", e.getMessage());
        }
    }
}
