package com.metroticket_api.auth;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.metroticket_api.security.ValidPassword;

/** Các request/response của /api/auth. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @ValidPassword String password,
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
            @NotBlank @ValidPassword String newPassword) {
    }

    public record LoginCodeRequest(
            @NotBlank String ticket,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "Mã gồm 6 chữ số") String code) {
    }

    public record LoginTicketRequest(@NotBlank String ticket) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record LogoutRequest(@NotBlank String refreshToken) {
    }

    /** Kết quả login: có token ngay, hoặc phải qua bước xác nhận 2 lớp. */
    public sealed interface LoginResponse permits TokenResponse, TwoFactorChallengeResponse {
    }

    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt,
            String refreshToken, Instant refreshTokenExpiresAt) implements LoginResponse {
    }

    public record TwoFactorChallengeResponse(boolean twoFactorRequired, String ticket, Instant expiresAt)
            implements LoginResponse {
    }
}
