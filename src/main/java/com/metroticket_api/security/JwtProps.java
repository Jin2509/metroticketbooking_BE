package com.metroticket_api.security;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** app.jwt.secret (>= 32 byte cho HS256), app.jwt.issuer, app.jwt.access-token-ttl */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProps(
        @NotNull @Size(min = 32, message = "app.jwt.secret phải dài tối thiểu 32 ký tự") String secret,
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl) {
}
