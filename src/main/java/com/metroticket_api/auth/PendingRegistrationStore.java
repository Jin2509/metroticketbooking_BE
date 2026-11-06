package com.metroticket_api.auth;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.metroticket_api.verification.VerificationProps;

import tools.jackson.databind.ObjectMapper;

/**
 * Giữ thông tin đăng ký chờ xác thực email, sống bằng thời hạn của mã. Bảng users chỉ có
 * tài khoản đã xác nhận OTP, nên không đọc/ghi được thì đăng ký phải lỗi, không fail-open
 * như LoginAttemptLimiter.
 */
@Component
public class PendingRegistrationStore {

    private static final Logger log = LoggerFactory.getLogger(PendingRegistrationStore.class);
    private static final String PREFIX = "auth:pending-registration:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final VerificationProps props;

    public PendingRegistrationStore(StringRedisTemplate redis, ObjectMapper objectMapper, VerificationProps props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public void save(String email, PendingRegistration registration) {
        redis.opsForValue().set(PREFIX + email, objectMapper.writeValueAsString(registration), props.lifetime());
    }

    public Optional<PendingRegistration> find(String email) {
        return Optional.ofNullable(redis.opsForValue().get(PREFIX + email))
                .map(json -> objectMapper.readValue(json, PendingRegistration.class));
    }

    /** Tài khoản đã tạo xong, không xoá được thì khoá cũng tự hết hạn. */
    public void delete(String email) {
        try {
            redis.delete(PREFIX + email);
        } catch (DataAccessException e) {
            log.warn("Không xoá được đăng ký chờ xác thực trong Redis: {}", e.getMessage());
        }
    }

    public record PendingRegistration(String passwordHash, String fullName) {
    }
}
