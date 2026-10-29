package com.metroticket_api.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Danh sách access token đã đăng xuất (theo jti), lưu trong Redis với TTL bằng
 * thời gian sống còn lại của token → tự xoá khi token hết hạn, không cần dọn rác.
 *
 * <p>Nếu Redis lỗi thì bỏ qua (fail-open): token đã đăng xuất vẫn dùng được tối đa
 * app.jwt.access-token-ttl, còn hệ thống không bị sập theo Redis.
 */
@Component
public class AccessTokenDenylist {

    private static final Logger log = LoggerFactory.getLogger(AccessTokenDenylist.class);
    private static final String PREFIX = "auth:revoked-jti:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public AccessTokenDenylist(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
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
        try {
            redis.opsForValue().set(PREFIX + jti, "1", ttl);
        } catch (DataAccessException e) {
            log.warn("Không ghi được jti vào Redis, access token sẽ hết hiệu lực khi hết hạn: {}", e.getMessage());
        }
    }

    public boolean isRevoked(String jti) {
        if (jti == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + jti));
        } catch (DataAccessException e) {
            log.warn("Không kiểm tra được jti trong Redis: {}", e.getMessage());
            return false;
        }
    }
}
