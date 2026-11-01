package com.metroticket_api.auth;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Chống dò mật khẩu: sai quá app.auth.login-max-failures lần liên tiếp thì khoá đăng nhập theo
 * email trong app.auth.login-lock-duration. Đếm cả email không tồn tại để không lộ tài khoản.
 * Redis lỗi thì bỏ qua (fail-open), giống AccessTokenDenylist.
 */
@Component
public class LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);
    private static final String PREFIX = "auth:login-failures:";

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final Duration lockDuration;

    public LoginAttemptLimiter(StringRedisTemplate redis,
            @Value("${app.auth.login-max-failures}") int maxFailures,
            @Value("${app.auth.login-lock-duration}") Duration lockDuration) {
        this.redis = redis;
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
    }

    public void ensureNotLocked(String email) {
        try {
            String count = redis.opsForValue().get(PREFIX + email);
            if (count != null && Long.parseLong(count) >= maxFailures) {
                throw new AuthException(HttpStatus.TOO_MANY_REQUESTS,
                        "Nhập sai mật khẩu quá nhiều lần, vui lòng thử lại sau " + lockDuration.toMinutes() + " phút");
            }
        } catch (DataAccessException e) {
            log.warn("Không đọc được bộ đếm đăng nhập trong Redis: {}", e.getMessage());
        }
    }

    public void recordFailure(String email) {
        try {
            redis.opsForValue().increment(PREFIX + email);
            redis.expire(PREFIX + email, lockDuration);
        } catch (DataAccessException e) {
            log.warn("Không ghi được bộ đếm đăng nhập vào Redis: {}", e.getMessage());
        }
    }

    public void reset(String email) {
        try {
            redis.delete(PREFIX + email);
        } catch (DataAccessException e) {
            log.warn("Không xoá được bộ đếm đăng nhập trong Redis: {}", e.getMessage());
        }
    }
}
