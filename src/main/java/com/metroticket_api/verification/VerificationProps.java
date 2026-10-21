package com.metroticket_api.verification;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Ánh xạ app.verification.* (hash-key, lifetime, resend-cooldown, max-failed-attempts).
 */
@Validated
@ConfigurationProperties(prefix = "app.verification")
public record VerificationProps(
        @NotNull @Size(min = 32, message = "hash-key phải dài tối thiểu 32 ký tự") String hashKey,
        @NotNull Duration lifetime,
        @NotNull Duration resendCooldown,
        @Min(1) int maxFailedAttempts) {
}
