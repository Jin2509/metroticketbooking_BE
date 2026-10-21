package com.metroticket_api.auth;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Các request/response của /api/auth. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 8, max = 72, message = "Mật khẩu từ 8 đến 72 ký tự") String password,
            @NotBlank @Size(max = 120) String fullName) {
    }

    public record VerifyEmailRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "Mã gồm 6 chữ số") String code) {
    }

    public record EmailRequest(@NotBlank @Email String email) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "Mã gồm 6 chữ số") String code,
            @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt) {
    }
}
